"""충전 부하 측정 도구(prepare.py, measure.py)가 함께 쓰는 HTTP·지표·DB 도우미입니다. 표준 라이브러리만 씁니다.

HTTP·Prometheus 도우미는 payment-service/load-test/lt_common.py와 같고, 서비스 목록과 psql 실행 도우미만 다릅니다.
"""
import json
import os
import pathlib
import re
import subprocess
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
DATA_FILE = HERE / "data" / "users.json"
RESULTS = HERE / "results"

# 로컬 전체 스택 주소. 다르면 환경 변수로 바꾼다
USER_URL = os.environ.get("USER_URL", "http://localhost:8081")
WALLET_URL = os.environ.get("WALLET_URL", "http://localhost:8082")
LEDGER_URL = os.environ.get("LEDGER_URL", "http://localhost:8085")
MOCK_BANK_URL = os.environ.get("MOCK_BANK_URL", "http://localhost:8090")
SERVICES = {"user": USER_URL, "wallet": WALLET_URL, "ledger": LEDGER_URL, "mock-bank": MOCK_BANK_URL}

# Postgres 컨테이너 안 psql로 읽고 쓴다(송금 measure.py와 같은 접속). 관리자 계정은 compose의 POSTGRES_USER(슈퍼유저)
PG_CONTAINER = os.environ.get("PG_CONTAINER", "travelpay-local-postgres-1")
PG_USER = os.environ.get("POSTGRES_USER", "travelpay_admin")
PG_DB = os.environ.get("POSTGRES_DB", "travelpay")
PSQL = ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-qAt", "-F", "\t",
        "-v", "ON_ERROR_STOP=1"]


class HttpResult:
    def __init__(self, status, body, headers=None):
        self.status = status
        self.body = body
        self.headers = headers or {}

    @property
    def data(self):
        return (self.body or {}).get("data") if isinstance(self.body, dict) else None

    @property
    def code(self):
        if not isinstance(self.body, dict):
            return None
        return (self.body.get("error") or {}).get("code")

    def __repr__(self):
        return f"HttpResult({self.status}, {self.code or ''})"


def request(method, url, headers=None, body=None, opener=None, timeout=30, raw=False):
    """HTTP 요청. 4xx·5xx도 예외 없이 상태와 본문을 돌려주고, 연결 실패는 상태 -1입니다."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    all_headers = {"Content-Type": "application/json"} if body is not None else {}
    all_headers.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=all_headers, method=method)
    open_ = opener.open if opener else urllib.request.urlopen
    try:
        with open_(req, timeout=timeout) as response:
            status, payload, response_headers = response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        status, payload, response_headers = error.code, error.read(), dict(error.headers)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
        return HttpResult(-1, {"connectError": str(getattr(error, "reason", error))})
    if raw:
        return HttpResult(status, payload.decode("utf-8", "replace"), response_headers)
    try:
        return HttpResult(status, json.loads(payload.decode("utf-8")) if payload else None, response_headers)
    except ValueError:
        return HttpResult(status, {"raw": payload.decode("utf-8", "replace")[:300]}, response_headers)


def member_headers(user_id, role="USER"):
    """Gateway가 넣어 줄 회원 헤더를 직접 넣는다(Gateway 제외 측정)."""
    return {"User-Id": str(user_id), "User-Role": role}


def load_data():
    if not DATA_FILE.exists():
        raise SystemExit(f"{DATA_FILE}가 없습니다. prepare.py를 먼저 실행하세요.")
    return json.loads(DATA_FILE.read_text(encoding="utf-8"))


# ---- psql ----

def psql(sql, timeout=60):
    """SQL을 실행하고 결과 행을 [[열, ...], ...]로 돌려줍니다. 실패하면 멈춥니다."""
    try:
        done = subprocess.run(PSQL, input=sql, capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SystemExit(f"psql 실행 실패({PG_CONTAINER}): {error}")
    if done.returncode != 0:
        raise SystemExit(f"psql 오류({PG_CONTAINER}, {PG_USER}): {done.stderr.strip()[:500]}")
    return [line.split("\t") for line in done.stdout.splitlines() if line]


def sql_text(value):
    """SQL 문자열 리터럴. 작은따옴표를 두 번 써서 감쌉니다."""
    return "'" + str(value).replace("'", "''") + "'"


def sql_array(values, element_type):
    """값 목록을 ARRAY[...]::type[] 리터럴로 만듭니다."""
    return "ARRAY[" + ",".join(sql_text(value) for value in values) + f"]::{element_type}[]"


# ---- Prometheus 지표 ----

_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)')
_LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def scrape(base_url, timeout=2):
    """/actuator/prometheus를 읽어 [(이름, 라벨 dict, 값)] 목록으로 돌려줍니다. 읽지 못하면 None."""
    result = request("GET", f"{base_url}/actuator/prometheus", timeout=timeout, raw=True)
    if result.status != 200:
        return None
    samples = []
    for line in result.body.splitlines():
        if not line or line.startswith("#"):
            continue
        match = _LINE.match(line)
        if not match:
            continue
        labels = dict(_LABEL.findall(match.group(3) or ""))
        try:
            samples.append((match.group(1), labels, float(match.group(4))))
        except ValueError:
            continue
    return samples


def summarize_scrape(samples):
    """측정에 쓰는 값만 뽑습니다: HTTP 서버 요청(경로·메서드별 횟수·합계), 커넥션 풀, CPU, 톰캣 스레드."""
    http = {}
    picked = {}
    for name, labels, value in samples:
        if name in ("http_server_requests_seconds_count", "http_server_requests_seconds_sum"):
            key = f"{labels.get('method')} {labels.get('uri')}"
            entry = http.setdefault(key, {"count": 0.0, "sum": 0.0})
            entry["count" if name.endswith("_count") else "sum"] += value
        elif name in ("hikaricp_connections_pending", "hikaricp_connections_active", "hikaricp_connections_max",
                      "system_cpu_usage", "process_cpu_usage", "tomcat_threads_busy_threads",
                      "tomcat_threads_config_max_threads"):
            picked[name] = picked.get(name, 0.0) + value
    return {"http": http, **picked}
