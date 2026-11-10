"""송금 부하 측정 도구(prepare.py, measure.py)가 함께 쓰는 HTTP·지표 도우미입니다. 표준 라이브러리만 씁니다."""
import json
import os
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
DATA_FILE = HERE / "data" / "users.json"
RESULTS = HERE / "results"

# 로컬 전체 스택 주소. 다르면 환경 변수로 바꾼다
USER_URL = os.environ.get("USER_URL", "http://localhost:8081")
WALLET_URL = os.environ.get("WALLET_URL", "http://localhost:8082")
PAYMENT_URL = os.environ.get("PAYMENT_URL", "http://localhost:8083")
LEDGER_URL = os.environ.get("LEDGER_URL", "http://localhost:8085")
SERVICES = {"user": USER_URL, "wallet": WALLET_URL, "payment": PAYMENT_URL, "ledger": LEDGER_URL}
# Gateway 경유 측정(--gateway)에서만 지표를 읽는 관리 포트 (API 포트 8080과 다름)
GATEWAY_METRICS_URL = os.environ.get("GATEWAY_METRICS_URL", "http://localhost:18080")


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


def krw_balance(user_id):
    result = request("GET", f"{WALLET_URL}/api/v1/wallets/me/balances", member_headers(user_id))
    if result.status != 200:
        raise RuntimeError(f"잔액 조회 실패 userId={user_id}: {result}")
    return next(item["balance"] for item in result.data if item["currency"] == "KRW")


def load_data():
    if not DATA_FILE.exists():
        raise SystemExit(f"{DATA_FILE}가 없습니다. prepare.py를 먼저 실행하세요.")
    return json.loads(DATA_FILE.read_text(encoding="utf-8"))


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
    """측정에 쓰는 값만 뽑습니다: HTTP 서버 요청(경로·메서드별 횟수·합계), 커넥션 풀(현재 값, 획득·점유 시간 누적),
    CPU, 톰캣 스레드, Heap 사용량, GC 정지 누적."""
    http = {}
    picked = {}
    for name, labels, value in samples:
        if name in ("http_server_requests_seconds_count", "http_server_requests_seconds_sum"):
            key = f"{labels.get('method')} {labels.get('uri')}"
            entry = http.setdefault(key, {"count": 0.0, "sum": 0.0})
            entry["count" if name.endswith("_count") else "sum"] += value
        elif name in ("hikaricp_connections_pending", "hikaricp_connections_active", "hikaricp_connections_max",
                      "system_cpu_usage", "process_cpu_usage", "tomcat_threads_busy_threads",
                      "tomcat_threads_config_max_threads",
                      # 누적값: 측정 구간 양끝의 차이로 평균을 낸다
                      "hikaricp_connections_acquire_seconds_count", "hikaricp_connections_acquire_seconds_sum",
                      "hikaricp_connections_usage_seconds_count", "hikaricp_connections_usage_seconds_sum",
                      "hikaricp_connections_timeout_total",
                      "jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_sum"):
            picked[name] = picked.get(name, 0.0) + value
        elif name in ("hikaricp_connections_acquire_seconds_max", "jvm_gc_pause_seconds_max"):
            picked[name] = max(picked.get(name, 0.0), value)
        elif name == "jvm_memory_used_bytes" and labels.get("area") == "heap":
            picked["jvm_heap_used_bytes"] = picked.get("jvm_heap_used_bytes", 0.0) + value
        elif name == "jvm_memory_max_bytes" and labels.get("area") == "heap" and value > 0:
            picked["jvm_heap_max_bytes"] = picked.get("jvm_heap_max_bytes", 0.0) + value
    return {"http": http, **picked}
