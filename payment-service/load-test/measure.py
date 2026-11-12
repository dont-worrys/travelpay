"""송금 부하 한 단계 측정: 사전 확인 → k6 실행(그동안 서비스·DB 지표를 1초마다 기록) → 지표 정리 → 대사(송금 요청 측정만).

로컬 전체 스택(PC)에 k6 컨테이너만 붙여 payment-service를 직접 호출합니다(Gateway 제외). README.md를 먼저 봅니다.

사용:
  python measure.py <LABEL> <VUS> [--mode spread|hot|sender-hot] [--amount 1]       # 송금 요청
  python measure.py <LABEL> <VUS> --gateway                                        # Gateway(8080) 경유, 세션 로그인
  python measure.py <LABEL> <VUS> --extra-service payment2=http://localhost:8086   # 추가 인스턴스 지표도 수집
  python measure.py <LABEL> <VUS> --script transfer-query.js [--query mixed]       # 송금 조회
  python measure.py <LABEL> <VUS> --mode hot --lock-holders                        # 원장 잠금 보유 시간 분해
결과: results/<LABEL>-vus<VUS>.json(k6) + results/<LABEL>-vus<VUS>-metrics.json(서비스·DB 지표, 대사)

측정 구간은 k6가 실제로 테스트를 시작한 시각(끝난 시각 - testRunDurationMs) + 예열부터 측정 시간만큼입니다.
서비스 지표는 그 구간 양끝에 가장 가까운 표본의 차이로 계산합니다(서버 쪽 평균 처리 시간 = 합계 차이 / 횟수 차이).
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

from lt_common import (GATEWAY_METRICS_URL, HERE, LEDGER_URL, PAYMENT_URL, RESULTS, SERVICES, krw_balance, load_data,
                       member_headers, request, scrape, summarize_scrape)

# 서비스별로 볼 HTTP 경로 (송금 요청 한 건이 지나는 곳). 추가 인스턴스(payment2 등)는 이름 끝 숫자를 뗀 역할로 찾는다
WATCHED = {
    "gateway": ["POST /api/v1/transfers", "POST UNKNOWN"],
    "payment": ["POST /api/v1/transfers", "GET /api/v1/transfers", "GET /api/v1/transfers/{transferId}"],
    "wallet": ["GET /internal/v1/wallets"],
    "user": ["GET /internal/v1/users/{userId}/real-name"],
    "ledger": ["POST /internal/v1/ledger/transactions"],
}
PG_CONTAINER = os.environ.get("PG_CONTAINER", "travelpay-local-postgres-1")
PG_USER = os.environ.get("POSTGRES_USER", "travelpay_admin")
PG_DB = os.environ.get("POSTGRES_DB", "travelpay")
# 원장·송금 DB 사용자의 잠금 대기·실행 중 세션, DB 교착 누적 수,
# 잠금을 기다리는 원장 세션의 현재 쿼리 경과 시간 합계·최대(ms). 잠금 대기는 FOR UPDATE 쿼리 시작부터이므로
# query_start 기준 경과 시간을 "지금까지 기다린 시간"으로 본다(표본 시점의 진행 중 대기라 실제 대기 시간보다 짧게 잡힘)
PG_QUERY = (
    "SELECT (extract(epoch FROM now()) * 1000)::bigint,"
    " count(*) FILTER (WHERE usename = 'ledger_app' AND wait_event_type = 'Lock'),"
    " count(*) FILTER (WHERE usename = 'ledger_app' AND state = 'active'),"
    " count(*) FILTER (WHERE usename = 'payment_app' AND state = 'active'),"
    " (SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()),"
    " coalesce(sum(extract(epoch FROM now() - query_start) * 1000)"
    "   FILTER (WHERE usename = 'ledger_app' AND wait_event_type = 'Lock'), 0)::bigint,"
    " coalesce(max(extract(epoch FROM now() - query_start) * 1000)"
    "   FILTER (WHERE usename = 'ledger_app' AND wait_event_type = 'Lock'), 0)::bigint"
    " FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
)
DB_SAMPLE_SECONDS = 0.5
MASTER = member_headers("00000000-0000-4000-8000-00000000a0a0", "MASTER")   # 관리자 조회용(측정 전용 ID)


def iso(moment):
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


# ---- 사전 확인 ----

def precheck(script, services):
    for name, url in services.items():
        health = request("GET", f"{url}/actuator/health", timeout=5)
        if health.status != 200:
            raise SystemExit(f"{name}({url})이 응답하지 않습니다: {health}")
    if script == "transfer-request.js":
        processing = admin_count(status="PROCESSING")
        if processing:
            raise SystemExit(f"PROCESSING 송금 {processing}건이 남아 있습니다. 복구가 끝난 뒤 측정합니다(복구 재전송이 섞임).")


def admin_count(**params):
    query = "&".join(f"{key}={value}" for key, value in params.items())
    result = request("GET", f"{PAYMENT_URL}/api/v1/admin/transfers?size=1&{query}", MASTER)
    if result.status != 200:
        raise SystemExit(f"관리자 송금 조회 실패: {result} {result.body}")
    return result.data["totalElements"]


# ---- 표본 수집 ----

class ServiceSampler(threading.Thread):
    """1초마다 서비스의 /actuator/prometheus를 읽어 (시각, 서비스별 요약)을 쌓습니다."""

    def __init__(self, services):
        super().__init__(daemon=True)
        self.services = services
        self.samples = []
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            started = time.time()
            snapshot = {}
            for name, url in self.services.items():
                samples = scrape(url)
                snapshot[name] = summarize_scrape(samples) if samples is not None else None
            self.samples.append((started, snapshot))
            self.stop.wait(max(0.0, 1.0 - (time.time() - started)))


class DbSampler(threading.Thread):
    """Postgres 컨테이너 안 psql \\watch로 0.5초마다 잠금 대기·세션·교착 수를 읽습니다. 실패하면 표본 없이 끝납니다."""

    def __init__(self, seconds):
        super().__init__(daemon=True)
        self.seconds = seconds
        self.samples = []
        self.error = None

    def run(self):
        command = ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-qAt", "-F", "\t"]
        script = f"{PG_QUERY}\n\\watch i={DB_SAMPLE_SECONDS} c={int(self.seconds / DB_SAMPLE_SECONDS)}\n"
        try:
            done = subprocess.run(command, input=script, capture_output=True, text=True, timeout=self.seconds + 30)
        except (OSError, subprocess.TimeoutExpired) as error:
            self.error = str(error)
            return
        if done.returncode != 0:
            self.error = done.stderr.strip()[:300]
        for line in done.stdout.splitlines():
            parts = line.split("\t")
            if len(parts) == 7 and parts[0].isdigit():
                values = [int(part) for part in parts]
                values[0] = values[0] / 1000   # 시각은 초 단위로
                self.samples.append(tuple(values))


# 원장 잠금을 쥐고 다른 원장 세션을 막고 있는 세션(holder)이 그 순간 무엇을 하는지.
# 한 줄 = 표본 하나: 시각(ms), 잠금 대기 세션 수, holder 목록(상태#대기 이벤트#쿼리 앞부분을 |로 이음).
# idle in transaction이면 DB는 할 일이 없고 앱이 다음 SQL을 보내길 기다리는 중(앱 처리 + 왕복 네트워크)
LOCK_HOLDER_QUERY = (
    "SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint,"
    " (SELECT count(*) FROM pg_stat_activity w WHERE w.usename = 'ledger_app' AND w.wait_event_type = 'Lock'),"
    " coalesce((SELECT string_agg(h.state || '#' || coalesce(h.wait_event_type || ':' || h.wait_event, '') || '#'"
    "   || left(regexp_replace(h.query, '[\\s|#]+', ' ', 'g'), 80), '|')"
    "   FROM pg_stat_activity h WHERE h.pid IN (SELECT unnest(pg_blocking_pids(w.pid)) FROM pg_stat_activity w"
    "   WHERE w.usename = 'ledger_app' AND w.wait_event_type = 'Lock')"
    # 행 잠금 대기열에서는 앞선 대기 세션도 blocking pid로 나온다. 스스로 잠금을 기다리는 세션은 빼고 실제 보유자만 본다
    "   AND h.wait_event_type IS DISTINCT FROM 'Lock'), '')"
)


class LockHolderSampler(threading.Thread):
    """--lock-holders: 짧은 간격으로 원장 잠금 holder의 상태를 표본으로 모읍니다(잠금 보유 시간 분해용).
    표본 쿼리도 DB·CPU를 쓰므로 기본 측정에서는 끕니다."""

    def __init__(self, seconds, interval):
        super().__init__(daemon=True)
        self.seconds = seconds
        self.interval = interval
        self.samples = []
        self.error = None

    def run(self):
        command = ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-qAt", "-F", "\t"]
        script = f"{LOCK_HOLDER_QUERY}\n\\watch i={self.interval} c={int(self.seconds / self.interval)}\n"
        try:
            done = subprocess.run(command, input=script, capture_output=True, text=True, encoding="utf-8",
                                  timeout=self.seconds + 60)
        except (OSError, subprocess.TimeoutExpired) as error:
            self.error = str(error)
            return
        if done.returncode != 0:
            self.error = done.stderr.strip()[:300]
        for line in done.stdout.splitlines():
            parts = line.split("\t")
            if len(parts) == 3 and parts[0].isdigit():
                holders = [holder.split("#", 2) for holder in parts[2].split("|") if holder]
                self.samples.append((int(parts[0]) / 1000, int(parts[1]), holders))


def holder_activity(holder):
    """holder 한 개를 '무엇을 하는 중인지' 한 단어로 묶습니다."""
    state, wait, query = (holder + ["", ""])[:3]
    text = query.strip().lower()
    if text.startswith("commit"):
        statement = "COMMIT"
    elif text.startswith("select") and ("for update" in text or "for no key update" in text):
        statement = "SELECT FOR UPDATE"
    else:
        words = text.split()
        if len(words) >= 3 and words[0] in ("insert", "delete") and words[1] in ("into", "from"):
            statement = f"{words[0].upper()} {words[2]}"
        elif len(words) >= 2 and words[0] == "update":
            statement = f"UPDATE {words[1]}"
        else:
            statement = words[0].upper() if words else "?"
    if state == "idle in transaction":
        return f"앱·네트워크 대기 (직전 {statement})"
    if state == "active":
        return f"DB 실행 {statement}" + (f" [{wait}]" if wait else "")
    return f"{state or '?'} {statement}"


def lock_holder_metrics(sampler, start, end, per_tx_hold_ms):
    if sampler is None:
        return None
    if sampler.error and not sampler.samples:
        return {"error": sampler.error}
    window = [sample for sample in sampler.samples if start <= sample[0] <= end]
    contended = [sample for sample in window if sample[1] > 0]
    counts = {}
    for _, _, holders in contended:
        for holder in holders:
            activity = holder_activity(holder)
            counts[activity] = counts.get(activity, 0) + 1
    total = sum(counts.values())
    breakdown = []
    for activity, count in sorted(counts.items(), key=lambda item: -item[1]):
        share = count / total
        row = {"activity": activity, "samples": count, "sharePercent": round(share * 100, 1)}
        if per_tx_hold_ms is not None:
            # 집중 계정은 잠금이 쉬지 않고 넘어가므로 건당 보유 시간 ≈ 측정 시간 / 거래 수. 그 시간을 표본 비율로 나눈다
            row["perTxMsEstimate"] = round(share * per_tx_hold_ms, 2)
        breakdown.append(row)
    return {
        "intervalSeconds": sampler.interval,
        "samples": len(window),
        "samplesWithWaiters": len(contended),
        "perTxHoldMsEstimate": per_tx_hold_ms,
        "breakdown": breakdown,
    }


# ---- 지표 계산 ----

def nearest(samples, moment):
    return min(samples, key=lambda sample: abs(sample[0] - moment))


PERCENTILES = (("p50Ms", 0.50), ("p95Ms", 0.95), ("p99Ms", 0.99), ("p999Ms", 0.999), ("maxBucketMs", 1.0))


def bucket_percentiles(before, after):
    """측정 구간 양끝의 누적 bucket 차이로 백분위를 냅니다. 값은 그 백분위가 들어간 bucket의 상한(ms)이라
    실제 값 이상으로 잡힙니다(보수적). bucket이 없으면 빈 dict."""
    bounds = sorted(after)
    if not bounds:
        return {}
    counts = [after[le] - before.get(le, 0.0) for le in bounds]
    total = counts[-1]
    if total <= 0:
        return {}
    result = {}
    for label, quantile in PERCENTILES:
        target = total * quantile
        upper = next(le for le, count in zip(bounds, counts) if count >= target)
        result[label] = None if upper == float("inf") else round(upper * 1000, 1)
    return result


def service_metrics(samples, start, end):
    window = [sample for sample in samples if start <= sample[0] <= end]
    result = {"samples": len(window)}
    if not window:
        return result
    first, last = nearest(samples, start), nearest(samples, end)
    names = sorted({name for _, snapshot in window for name in snapshot})
    for name in names:
        paths = WATCHED.get(name.rstrip("0123456789"), [])
        before, after = first[1].get(name), last[1].get(name)
        service = {}
        if before and after:
            for path in paths:
                b = before["http"].get(path, {"count": 0, "sum": 0})
                a = after["http"].get(path, {"count": 0, "sum": 0})
                calls = a["count"] - b["count"]
                if calls > 0:
                    # _max 게이지는 최근 약 2분의 최대라 앞 측정 값이 섞이므로 쓰지 않고, 구간 bucket으로 최대를 낸다.
                    # 서버 쪽 처리 시간이다. 호출하는 쪽이 본 시간은 여기에 네트워크·HTTP 클라이언트 시간이 더해진다
                    service[path] = {"calls": int(calls), "avgMs": round((a["sum"] - b["sum"]) / calls * 1000, 2),
                                     **bucket_percentiles(b.get("buckets", {}), a.get("buckets", {}))}
        gauges = [snapshot[1][name] for snapshot in window if snapshot[1].get(name)]
        for gauge, label in (("hikaricp_connections_pending", "hikariPending"),
                             ("hikaricp_connections_active", "hikariActive"),
                             ("tomcat_threads_busy_threads", "tomcatBusyThreads")):
            values = [g[gauge] for g in gauges if gauge in g]
            if values:
                service[label] = {"avg": round(sum(values) / len(values), 2), "max": max(values)}
        if before and after:
            for prefix, label in (("hikaricp_connections_acquire_seconds", "hikariAcquireAvgMs"),
                                  ("hikaricp_connections_usage_seconds", "hikariUsageAvgMs")):
                calls = after.get(f"{prefix}_count", 0) - before.get(f"{prefix}_count", 0)
                if calls > 0:
                    service[label] = round((after.get(f"{prefix}_sum", 0) - before.get(f"{prefix}_sum", 0))
                                           / calls * 1000, 3)
            timeouts = after.get("hikaricp_connections_timeout_total", 0) - before.get("hikaricp_connections_timeout_total", 0)
            service["hikariTimeouts"] = int(timeouts)
            gc_count = after.get("jvm_gc_pause_seconds_count", 0) - before.get("jvm_gc_pause_seconds_count", 0)
            gc_sum = after.get("jvm_gc_pause_seconds_sum", 0) - before.get("jvm_gc_pause_seconds_sum", 0)
            service["gcPauses"] = {"count": int(gc_count), "totalMs": round(gc_sum * 1000, 1)}
        acquire_max = [g["hikaricp_connections_acquire_seconds_max"] for g in gauges
                       if "hikaricp_connections_acquire_seconds_max" in g]
        if acquire_max:
            service["hikariAcquireMaxMs"] = round(max(acquire_max) * 1000, 1)
        gc_max = [g["jvm_gc_pause_seconds_max"] for g in gauges if "jvm_gc_pause_seconds_max" in g]
        if gc_max:
            service["gcPauseMaxMs"] = round(max(gc_max) * 1000, 1)
        heap = [g["jvm_heap_used_bytes"] for g in gauges if "jvm_heap_used_bytes" in g]
        if heap:
            third = max(1, len(heap) // 3)
            service["heapMb"] = {"avg": round(sum(heap) / len(heap) / 2**20, 1), "max": round(max(heap) / 2**20, 1),
                                 # 측정 구간 처음 1/3과 마지막 1/3의 평균. 꾸준히 오르면 누수 후보
                                 "firstThirdAvg": round(sum(heap[:third]) / third / 2**20, 1),
                                 "lastThirdAvg": round(sum(heap[-third:]) / third / 2**20, 1)}
        heap_max = [g["jvm_heap_max_bytes"] for g in gauges if "jvm_heap_max_bytes" in g]
        if heap_max:
            service["heapMaxMb"] = round(heap_max[0] / 2**20, 1)
        cpu = [g["process_cpu_usage"] for g in gauges if "process_cpu_usage" in g]
        if cpu:
            service["processCpuPercentAvg"] = round(sum(cpu) / len(cpu) * 100, 1)
        if gauges and gauges[0].get("hikaricp_connections_max") is not None:
            service["hikariMax"] = gauges[0]["hikaricp_connections_max"]
        result[name] = service
    system = [snapshot[1]["payment"]["system_cpu_usage"] for snapshot in window
              if snapshot[1].get("payment") and "system_cpu_usage" in snapshot[1]["payment"]]
    if system:
        result["systemCpuPercent"] = {"avg": round(sum(system) / len(system) * 100, 1),
                                      "max": round(max(system) * 100, 1)}
    return result


def db_metrics(sampler, start, end):
    if sampler.error and not sampler.samples:
        return {"error": sampler.error}
    window = [sample for sample in sampler.samples if start <= sample[0] <= end]
    if not window:
        return {"samples": 0}

    def stat(index):
        values = [sample[index] for sample in window]
        return {"avg": round(sum(values) / len(values), 2), "max": max(values)}

    return {
        "samples": len(window),
        "ledgerLockWaits": stat(1),
        "ledgerActiveSessions": stat(2),
        "paymentActiveSessions": stat(3),
        "deadlocksDuringMeasure": window[-1][4] - window[0][4],
        # 표본 시점에 잠금을 기다리던 원장 세션들의 "지금까지 기다린 시간"(ms)
        "ledgerLockWaitAgeMs": {
            "avgPerWaiter": round(sum(sample[5] for sample in window)
                                  / max(1, sum(sample[1] for sample in window)), 1),
            "max": max(sample[6] for sample in window),
        },
    }


# ---- 대사 (송금 요청 측정) ----

def wait_processing(created_from, created_to, timeout_seconds):
    """측정 구간에 만든 송금 중 PROCESSING이 없어질 때까지 기다리고 걸린 초를 돌려줍니다(복구 스케줄러가 확정)."""
    started = time.monotonic()
    while True:
        left = admin_count(status="PROCESSING", **{"from": iso(created_from), "to": iso(created_to)})
        if left == 0:
            return round(time.monotonic() - started, 1)
        if time.monotonic() - started > timeout_seconds:
            return None
        print(f"  PROCESSING {left}건 복구 대기", flush=True)
        time.sleep(10)


def fetch_transfers(created_from, created_to):
    transfers, page = [], 0
    while True:
        result = request("GET", f"{PAYMENT_URL}/api/v1/admin/transfers?from={iso(created_from)}&to={iso(created_to)}"
                                f"&orderType=OLDEST&size=100&page={page}", MASTER)
        if result.status != 200:
            raise SystemExit(f"관리자 송금 목록 실패: {result} {result.body}")
        transfers.extend(result.data["items"])
        page += 1
        if page >= result.data["totalPages"]:
            return transfers


def fetch_ledger(created_from, created_to):
    """원장 기간 목록(TRANSFER). 닫힌 구간을 끝까지 읽고 transactionId로 중복을 없앱니다."""
    items, cursor = {}, None
    base = (f"{LEDGER_URL}/internal/v1/ledger/transactions/period?referenceType=TRANSFER"
            f"&from={created_from.strftime('%Y-%m-%dT%H:%M:%SZ')}&to={created_to.strftime('%Y-%m-%dT%H:%M:%SZ')}&size=100")
    while True:
        result = request("GET", base + (f"&cursor={cursor}" if cursor else ""))
        if result.status != 200:
            raise SystemExit(f"원장 기간 목록 실패: {result} {result.body}")
        for item in result.data["items"]:
            items[item["transactionId"]] = item
        cursor = result.data.get("nextCursor")
        if not cursor:
            return list(items.values())


def reconcile(data, balances_before, created_from, created_to, recovery_timeout):
    """측정 구간 송금과 원장 거래를 맞춰 보고 불변식 위반을 모읍니다."""
    recovery_seconds = wait_processing(created_from, created_to, recovery_timeout)
    time.sleep(5)   # 원장 거래 기록 트랜잭션 제한(3초)보다 넉넉히 지난 닫힌 구간을 읽는다
    transfers = fetch_transfers(created_from, created_to)
    ledger_to = datetime.now(timezone.utc).replace(microsecond=0)
    ledger = fetch_ledger(created_from.replace(microsecond=0) - timedelta(seconds=1), ledger_to)
    by_reference = {}
    for item in ledger:
        by_reference.setdefault(item["referenceId"], []).append(item)

    violations, statuses = [], {}
    completed_ids = set()
    participants = {pair["senderId"] for pair in data["pairs"]} | {pair["receiverId"] for pair in data["pairs"]}
    participants.add(data["hotReceiverId"])
    expected_balance = dict(balances_before)
    for transfer in transfers:
        transfer_id, status = transfer["transferId"], transfer["status"]
        statuses[status] = statuses.get(status, 0) + 1
        records = by_reference.get(transfer_id, [])
        posted = [record for record in records if record["status"] == "POSTED"]
        if len(records) > 1:
            violations.append(f"원장 거래가 2건 이상 {transfer_id}")
        if status == "COMPLETED":
            completed_ids.add(transfer_id)
            if len(posted) != 1:
                violations.append(f"COMPLETED인데 원장 POSTED가 {len(posted)}건 {transfer_id}")
            elif not entries_match(posted[0], transfer):
                violations.append(f"원장 항목이 송금과 다름 {transfer_id}")
            amount = transfer["amount"]
            expected_balance[transfer["senderId"]] = expected_balance.get(transfer["senderId"], 0) - amount
            expected_balance[transfer["receiverId"]] = expected_balance.get(transfer["receiverId"], 0) + amount
        elif status == "FAILED" and posted:
            violations.append(f"FAILED인데 원장 POSTED {transfer_id}")
        elif status == "PROCESSING":
            violations.append(f"복구 후에도 PROCESSING {transfer_id}")
    transfer_ids = {transfer["transferId"] for transfer in transfers}
    orphans = [item["referenceId"] for item in ledger
               if item["status"] == "POSTED" and item["referenceId"] not in transfer_ids]
    if orphans:
        violations.append(f"송금 없이 원장만 POSTED {len(orphans)}건 (예: {orphans[:3]})")

    balances_after = {user: krw_balance(user) for user in participants}
    for user in participants:
        if balances_after[user] < 0:
            violations.append(f"음수 잔액 {user}: {balances_after[user]}")
        if balances_after[user] != expected_balance.get(user, balances_before[user]):
            violations.append(f"잔액 불일치 {user}: 기대 {expected_balance.get(user)} 실제 {balances_after[user]}")
    if sum(balances_after.values()) != sum(balances_before.values()):
        violations.append(f"잔액 합계 변화 {sum(balances_before.values())} → {sum(balances_after.values())}")

    return {
        "transfers": len(transfers),
        "statuses": statuses,
        "ledgerTransactions": len(ledger),
        "recoverySeconds": recovery_seconds,
        "balanceTotal": {"before": sum(balances_before.values()), "after": sum(balances_after.values())},
        "violations": violations,
    }


def entries_match(record, transfer):
    """원장 항목이 송금인 지갑 DEBIT TRANSFER_OUT·수취인 지갑 CREDIT TRANSFER_IN·같은 통화·금액인지(순서 무관)."""
    expected = {("USER", transfer["sourceWalletId"], transfer["currency"], "DEBIT", transfer["amount"], "TRANSFER_OUT"),
                ("USER", transfer["targetWalletId"], transfer["currency"], "CREDIT", transfer["amount"], "TRANSFER_IN")}
    actual = {(entry["ownerType"], entry["walletId"], entry["currency"], entry["direction"], entry["amount"],
               entry["entryType"]) for entry in record.get("entries") or []}
    return actual == expected


# ---- 실행 ----

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("label")
    parser.add_argument("vus", type=int)
    parser.add_argument("--script", default="transfer-request.js", choices=["transfer-request.js", "transfer-query.js"])
    parser.add_argument("--mode", default="spread", choices=["spread", "hot", "sender-hot", "bidirectional", "mixed"])
    parser.add_argument("--hot-vus", type=int, default=None,
                        help="mixed에서 집중 수취인에게 보내는 가상 사용자 수 (기본 VUS/2, 0이면 spread 그룹만)")
    parser.add_argument("--amount", type=int, default=1)
    parser.add_argument("--query", default="mixed", choices=["list", "detail", "mixed"])
    parser.add_argument("--warmup", type=int, default=15)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--gateway", action="store_true",
                        help="Gateway(8080)를 거쳐 세션 로그인·CSRF로 호출 (data/users.json에 senderEmail 필요)")
    parser.add_argument("--extra-service", action="append", default=[], metavar="NAME=URL",
                        help="지표를 함께 읽을 추가 인스턴스 (예: payment2=http://localhost:8086)")
    parser.add_argument("--recovery-timeout", type=int, default=600,
                        help="측정 후 PROCESSING 복구를 기다릴 최대 초 (결과 모름 5회 백오프 합계 약 7분 30초)")
    parser.add_argument("--lock-holders", action="store_true",
                        help="원장 잠금 holder 상태를 짧은 간격으로 표본 수집 (잠금 보유 시간 분해, --mode hot과 함께)")
    parser.add_argument("--lock-holder-interval", type=float, default=0.05, help="holder 표본 간격(초)")
    args = parser.parse_args()
    is_request = args.script == "transfer-request.js"

    data = load_data()
    RESULTS.mkdir(exist_ok=True)
    k6_result = RESULTS / f"{args.label}-vus{args.vus}.json"
    metrics_result = RESULTS / f"{args.label}-vus{args.vus}-metrics.json"
    for stale in (k6_result, metrics_result):
        stale.unlink(missing_ok=True)   # 앞 실행 결과를 이번 결과로 오인하지 않게

    services = dict(SERVICES)
    if args.gateway:
        services["gateway"] = GATEWAY_METRICS_URL
    for item in args.extra_service:
        name, url = item.split("=", 1)
        services[name] = url

    print("[1/4] 사전 확인 (서비스 health, 남은 PROCESSING)")
    precheck(args.script, services)
    participants = {pair["senderId"] for pair in data["pairs"]} | {pair["receiverId"] for pair in data["pairs"]}
    participants.add(data["hotReceiverId"])
    balances_before = {user: krw_balance(user) for user in participants} if is_request else {}

    print(f"[2/4] k6 실행 ({args.script}, VUS={args.vus}), 서비스·DB 지표 1초 간격 기록")
    service_sampler = ServiceSampler(services)
    db_sampler = DbSampler(args.warmup + args.duration + 60)
    holder_sampler = (LockHolderSampler(args.warmup + args.duration + 60, args.lock_holder_interval)
                      if args.lock_holders else None)
    service_sampler.start()
    db_sampler.start()
    if holder_sampler:
        holder_sampler.start()
    run_started = datetime.now(timezone.utc)
    env = {"VUS": str(args.vus), "LABEL": args.label, "WARMUP": f"{args.warmup}s", "DURATION": f"{args.duration}s",
           "MODE": args.mode, "AMOUNT": str(args.amount), "QUERY": args.query,
           "GATEWAY": "1" if args.gateway else "0", "HOT_VUS": "" if args.hot_vus is None else str(args.hot_vus)}
    if args.gateway:
        env["BASE_URL"] = "http://host.docker.internal:8080"
    command = ["docker", "compose", "run", "--rm"]
    for key, value in env.items():
        command += ["-e", f"{key}={value}"]
    command += ["k6", "run", "--quiet", f"/scripts/{args.script}"]
    k6 = subprocess.run(command, cwd=HERE)
    k6_ended = time.time()
    service_sampler.stop.set()
    service_sampler.join()
    if k6.returncode != 0 or not k6_result.exists():
        raise SystemExit(f"k6 실행 실패(종료 코드 {k6.returncode}). 결과 파일이 없습니다: {k6_result}")

    summary = json.loads(k6_result.read_text(encoding="utf-8"))
    measure_from = k6_ended - summary["testRunDurationMs"] / 1000 + args.warmup
    measure_to = measure_from + args.duration

    print("[3/4] 지표 정리")
    db_sampler.join(timeout=args.warmup + args.duration + 120)
    metrics = {
        "label": args.label, "vus": args.vus, "script": args.script,
        "mode": args.mode if is_request else None, "hotVus": args.hot_vus, "query": None if is_request else args.query,
        "gateway": args.gateway, "extraServices": args.extra_service,
        "measureWindow": {"from": iso(datetime.fromtimestamp(measure_from, timezone.utc)),
                          "to": iso(datetime.fromtimestamp(measure_to, timezone.utc))},
        "services": service_metrics(service_sampler.samples, measure_from, measure_to),
        "db": db_metrics(db_sampler, measure_from, measure_to),
    }
    ledger_calls = sum(service.get("POST /internal/v1/ledger/transactions", {}).get("calls", 0)
                       for name, service in metrics["services"].items()
                       if isinstance(service, dict) and name.rstrip("0123456789") == "ledger")
    lock_waits = (metrics["db"].get("ledgerLockWaits") or {}).get("avg")
    if ledger_calls and lock_waits is not None:
        # 리틀 법칙 L = λW → 거래 한 건이 잠금을 기다린 평균 시간 W = 평균 대기 세션 수 / 초당 거래 수
        metrics["db"]["ledgerLockWaitPerTxMsEstimate"] = round(lock_waits / (ledger_calls / args.duration) * 1000, 1)
    if holder_sampler:
        holder_sampler.join(timeout=args.warmup + args.duration + 120)
        per_tx_hold_ms = round(args.duration * 1000 / ledger_calls, 2) if ledger_calls else None
        metrics["db"]["lockHolders"] = lock_holder_metrics(holder_sampler, measure_from, measure_to, per_tx_hold_ms)

    if is_request:
        print("[4/4] 대사: PROCESSING 복구 대기 → 송금·원장·잔액 대조")
        created_from = run_started - timedelta(seconds=5)
        created_to = datetime.fromtimestamp(k6_ended + 5, timezone.utc)
        metrics["reconciliation"] = reconcile(data, balances_before, created_from, created_to, args.recovery_timeout)
    else:
        print("[4/4] 조회 측정이라 대사는 하지 않습니다")

    metrics_result.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    violations = (metrics.get("reconciliation") or {}).get("violations") or []
    failures = summary["statusCounts"]["429"] + summary["statusCounts"]["4xxOther"] + \
        summary["statusCounts"]["5xx"] + summary["statusCounts"]["noResponse"]
    if violations or failures:
        print(f"\n주의: 오류 응답 {failures}건, 불변식 위반 {len(violations)}건. 이 측정은 원인을 확인하기 전까지 기준값으로 쓰지 않습니다.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
