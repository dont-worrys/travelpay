"""충전 부하 한 번 측정: 사전 확인 → k6 실행(서비스·DB 지표 1초 표본) → 종결 대기 → 지표 → 출금 정합성 → 대사.

로컬 전체 스택(PC)에 k6 컨테이너만 붙여 wallet-service를 직접 호출합니다(Gateway 제외). README.md를 먼저 봅니다.

사용:
  python measure.py <LABEL> --count 1000 --rate 50 --concurrency 1 [--amount 10000] [--pre-vus N] [--max-vus N]
                    [--drain-timeout 600]
결과: results/<LABEL>-c<CONCURRENCY>.json(k6) + results/<LABEL>-c<CONCURRENCY>-metrics.json(서버·DB 지표, 정합성, 대사)

--concurrency는 결과에 남기는 값입니다. 실제 동시성은 지갑 실행 인자로 바꾸고 재기동합니다(스크립트가 바꾸지 않음).
서비스 지표 구간은 k6 시작 ~ 마지막 종결 시각입니다(접수 뒤 SAGA가 은행·원장을 부르므로 종결까지 봅니다).
"""
import argparse
import json
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

from lt_common import (HERE, PSQL, RESULTS, SERVICES, WALLET_URL, load_data, member_headers, psql, request, scrape,
                       sql_array, sql_text, summarize_scrape)

# 서비스별로 볼 HTTP 경로 (충전 한 건이 지나는 곳)
WATCHED = {
    "wallet": ["POST /api/v1/charges"],
    "user": ["GET /internal/v1/users/{userId}/funding-identity"],
    "ledger": ["POST /internal/v1/ledger/transactions"],
    "mock-bank": ["POST /internal/v1/bank/withdrawals", "POST /internal/v1/bank/refunds"],
}
TERMINAL = ("COMPLETED", "FAILED", "REFUNDED", "MANUAL_REVIEW")
TERMINAL_SQL = "(" + ",".join(f"'{status}'" for status in TERMINAL) + ")"
# 원장 잠금 대기·실행 중 세션, 지갑 실행 중 세션, DB 교착 누적 수, Outbox 미발행 수
PG_QUERY = (
    "SELECT extract(epoch FROM now())::bigint,"
    " count(*) FILTER (WHERE usename = 'ledger_app' AND wait_event_type = 'Lock'),"
    " count(*) FILTER (WHERE usename = 'ledger_app' AND state = 'active'),"
    " count(*) FILTER (WHERE usename = 'wallet_app' AND state = 'active'),"
    " (SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()),"
    " (SELECT count(*) FROM wallet_service.outbox_events WHERE status = 'PENDING')"
    " FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
)
PG_COLUMNS = 6
MASTER = member_headers("00000000-0000-4000-8000-00000000a0a0", "MASTER")   # 관리자 대사용(측정 전용 ID)
RECONCILIATION_GRACE_SECONDS = 10   # 지갑 실행 인자 grace=10s와 같게
RECONCILIATION_URL = f"{WALLET_URL}/api/v1/admin/charges/reconciliations"


def iso(moment):
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


# ---- 사전 확인 ----

def precheck(data):
    for name, url in SERVICES.items():
        health = request("GET", f"{url}/actuator/health", timeout=5)
        if health.status != 200:
            raise SystemExit(f"{name}({url})이 응답하지 않습니다: {health}")
    in_progress = int(psql(f"SELECT count(*) FROM wallet_service.charge_requests WHERE status NOT IN {TERMINAL_SQL};")[0][0])
    if in_progress:
        raise SystemExit(f"처리 중 충전 {in_progress}건이 남아 있습니다. 모두 종결된 뒤 측정합니다(복구 재실행이 섞임).")
    accounts = int(psql(f"SELECT count(*) FROM ({accounts_sql(data)}) a;")[0][0])
    if accounts != len(data["users"]):
        raise SystemExit(f"mock-bank 측정 계좌가 {accounts}개입니다(회원 {len(data['users'])}명). prepare.py를 다시 실행하세요.")


def accounts_sql(data):
    numbers = [user["accountNumber"] for user in data["users"]]
    return (f"SELECT id FROM mock_bank.bank_accounts WHERE bank_code = {sql_text(data['bankCode'])}"
            f" AND account_number = ANY({sql_array(numbers, 'varchar')})")


def bank_balance(data):
    return int(psql(f"SELECT coalesce(sum(balance), 0) FROM mock_bank.bank_accounts WHERE id IN ({accounts_sql(data)});")[0][0])


# ---- 표본 수집 ----

class ServiceSampler(threading.Thread):
    """1초마다 네 서비스의 /actuator/prometheus를 읽어 (시각, 서비스별 요약)을 쌓습니다."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            started = time.time()
            snapshot = {}
            for name, url in SERVICES.items():
                samples = scrape(url)
                snapshot[name] = summarize_scrape(samples) if samples is not None else None
            self.samples.append((started, snapshot))
            self.stop.wait(max(0.0, 1.0 - (time.time() - started)))


class DbSampler(threading.Thread):
    """Postgres 컨테이너 안 psql \\watch로 1초마다 잠금 대기·세션·교착·Outbox 수를 읽습니다.

    측정 길이(종결 대기 포함)를 미리 모르므로 30회짜리 \\watch를 멈출 때까지 이어서 실행합니다.
    실패하면 표본 없이 끝나고 측정은 계속됩니다(db.error).
    """
    CHUNK = 30

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self.error = None
        self.stop = threading.Event()

    def run(self):
        script = f"{PG_QUERY}\n\\watch i=1 c={self.CHUNK}\n"
        while not self.stop.is_set():
            try:
                done = subprocess.run(PSQL, input=script, capture_output=True, text=True, encoding="utf-8",
                                      timeout=self.CHUNK + 30)
            except (OSError, subprocess.TimeoutExpired) as error:
                self.error = str(error)
                return
            for line in done.stdout.splitlines():
                parts = line.split("\t")
                if len(parts) == PG_COLUMNS and parts[0].isdigit():
                    self.samples.append(tuple(int(part) for part in parts))
            if done.returncode != 0:
                self.error = done.stderr.strip()[:300]
                return


# ---- 서버·DB 지표 ----

def nearest(samples, moment):
    return min(samples, key=lambda sample: abs(sample[0] - moment))


def service_metrics(samples, start, end):
    window = [sample for sample in samples if start <= sample[0] <= end]
    result = {"samples": len(window)}
    if not window:
        return result
    first, last = nearest(samples, start), nearest(samples, end)
    for name, paths in WATCHED.items():
        before, after = first[1].get(name), last[1].get(name)
        service = {}
        if before and after:
            for path in paths:
                b = before["http"].get(path, {"count": 0, "sum": 0})
                a = after["http"].get(path, {"count": 0, "sum": 0})
                calls = a["count"] - b["count"]
                if calls > 0:
                    service[path] = {"calls": int(calls), "avgMs": round((a["sum"] - b["sum"]) / calls * 1000, 2)}
        gauges = [snapshot[1][name] for snapshot in window if snapshot[1].get(name)]
        for gauge, label in (("hikaricp_connections_pending", "hikariPending"),
                             ("hikaricp_connections_active", "hikariActive"),
                             ("tomcat_threads_busy_threads", "tomcatBusyThreads")):
            values = [g[gauge] for g in gauges if gauge in g]
            if values:
                service[label] = {"avg": round(sum(values) / len(values), 2), "max": max(values)}
        cpu = [g["process_cpu_usage"] for g in gauges if "process_cpu_usage" in g]
        if cpu:
            service["processCpuPercentAvg"] = round(sum(cpu) / len(cpu) * 100, 1)
        if gauges and gauges[0].get("hikaricp_connections_max") is not None:
            service["hikariMax"] = gauges[0]["hikaricp_connections_max"]
        result[name] = service
    system = [snapshot[1]["wallet"]["system_cpu_usage"] for snapshot in window
              if snapshot[1].get("wallet") and "system_cpu_usage" in snapshot[1]["wallet"]]
    if system:
        result["systemCpuPercent"] = {"avg": round(sum(system) / len(system) * 100, 1),
                                      "max": round(max(system) * 100, 1)}
    return result


def db_metrics(sampler, start, end):
    if sampler.error and not sampler.samples:
        return {"error": sampler.error}
    window = [sample for sample in sampler.samples if start <= sample[0] <= end]
    if not window:
        return {"samples": 0, "error": sampler.error}

    def stat(index):
        values = [sample[index] for sample in window]
        return {"avg": round(sum(values) / len(values), 2), "max": max(values)}

    return {
        "samples": len(window),
        "ledgerLockWaits": stat(1),
        "ledgerActiveSessions": stat(2),
        "walletActiveSessions": stat(3),
        "deadlocksDuringMeasure": window[-1][4] - window[0][4],
        "outboxPending": stat(5),
        "error": sampler.error,
    }


# ---- 충전 지표 (wallet DB) ----

def measured_sql(data, since):
    """이번 측정의 충전: 측정 회원 + created_at ≥ since"""
    user_ids = [user["userId"] for user in data["users"]]
    return (f"SELECT * FROM wallet_service.charge_requests WHERE user_id = ANY({sql_array(user_ids, 'uuid')})"
            f" AND created_at >= {sql_text(iso(since))}::timestamptz")


def wait_terminal(data, since, timeout_seconds):
    """이번 측정 충전이 모두 종결될 때까지 기다리고 걸린 초를 돌려줍니다. 시간을 넘기면 None."""
    started = time.monotonic()
    while True:
        left = int(psql(f"SELECT count(*) FROM ({measured_sql(data, since)}) m WHERE status NOT IN {TERMINAL_SQL};")[0][0])
        if left == 0:
            return round(time.monotonic() - started, 1)
        if time.monotonic() - started > timeout_seconds:
            print(f"  {timeout_seconds}초 안에 종결되지 않은 충전 {left}건", flush=True)
            return None
        print(f"  처리 중 {left}건 종결 대기", flush=True)
        time.sleep(5)


def charge_metrics(data, since):
    measured = measured_sql(data, since)
    statuses = {row[0]: {"count": int(row[1]), "amount": int(row[2])}
                for row in psql(f"SELECT status, count(*), coalesce(sum(amount), 0) FROM ({measured}) m GROUP BY status;")}
    accepted = sum(entry["count"] for entry in statuses.values())
    if accepted == 0:
        return {"accepted": 0, "statuses": statuses}
    # 첫 접수 시각은 대사 기간 시작으로 그대로 쓰므로 마이크로초 문자열로 받는다
    first_created_iso, first_created, last_terminal = psql(
        "SELECT to_char(min(m.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),"
        " extract(epoch FROM min(m.created_at)),"
        " (SELECT extract(epoch FROM max(h.created_at)) FROM wallet_service.charge_status_history h"
        f"   WHERE h.charge_id IN (SELECT id FROM ({measured}) x) AND h.to_status IN {TERMINAL_SQL})"
        f" FROM ({measured}) m;")[0]
    p50, p95, completed_count = psql(
        "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY ms), percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), count(*)"
        " FROM (SELECT extract(epoch FROM (min(h.created_at) - m.created_at)) * 1000 AS ms"
        f"       FROM ({measured}) m JOIN wallet_service.charge_status_history h ON h.charge_id = m.id"
        "       WHERE h.to_status = 'COMPLETED' GROUP BY m.id, m.created_at) c;")[0]
    first_created, last_terminal = float(first_created), float(last_terminal) if last_terminal else None
    span = (last_terminal - first_created) if last_terminal else None
    return {
        "accepted": accepted,
        "statuses": statuses,
        "firstCreatedAt": first_created_iso,
        "firstCreatedEpoch": first_created,
        "lastTerminalAt": iso(datetime.fromtimestamp(last_terminal, timezone.utc)) if last_terminal else None,
        "lastTerminalEpoch": last_terminal,
        "spanSeconds": round(span, 3) if span else None,
        # 완료 처리량 = 실제 접수 건수 ÷ (첫 접수 ~ 마지막 종결)
        "completionTps": round(accepted / span, 2) if span else None,
        "completionLatencyMs": {
            "count": int(completed_count),
            "p50": round(float(p50), 1) if p50 else None,
            "p95": round(float(p95), 1) if p95 else None,
        },
    }


# ---- 출금 정합성 (mock-bank DB) ----

def bank_consistency(data, since, statuses, balance_before):
    rows = psql(
        "SELECT type, count(*), coalesce(sum(amount), 0) FROM mock_bank.bank_transactions"
        f" WHERE status = 'SUCCEEDED' AND created_at >= {sql_text(iso(since))}::timestamptz"
        f" AND ((type = 'DEBIT' AND from_account_id IN ({accounts_sql(data)}))"
        f"   OR (type = 'REFUND' AND to_account_id IN ({accounts_sql(data)})))"
        " GROUP BY type;")
    bank = {row[0]: {"count": int(row[1]), "amount": int(row[2])} for row in rows}
    debit = bank.get("DEBIT", {"count": 0, "amount": 0})
    refund = bank.get("REFUND", {"count": 0, "amount": 0})
    empty = {"count": 0, "amount": 0}
    completed, refunded = statuses.get("COMPLETED", empty), statuses.get("REFUNDED", empty)
    expected_debit = {"count": completed["count"] + refunded["count"], "amount": completed["amount"] + refunded["amount"]}
    balance_after = bank_balance(data)
    decrease = balance_before - balance_after
    violations = []
    if debit != expected_debit:
        violations.append(f"DEBIT 성공 {debit} ≠ COMPLETED·REFUNDED 충전 {expected_debit}")
    if refund != refunded:
        violations.append(f"REFUND 성공 {refund} ≠ REFUNDED 충전 {refunded}")
    if decrease != debit["amount"] - refund["amount"]:
        violations.append(f"계좌 잔액 감소 {decrease} ≠ DEBIT 합계 − REFUND 합계 {debit['amount'] - refund['amount']}")
    return {
        "debit": debit,
        "refund": refund,
        "expectedDebit": expected_debit,
        "expectedRefund": refunded,
        "balance": {"before": balance_before, "after": balance_after, "decrease": decrease},
        "violations": violations,
    }


# ---- 대사 (운영 API) ----

def reconcile(first_created_iso, last_terminal):
    """마지막 종결 후 grace보다 넉넉히 기다린 뒤 [첫 접수, 지금 − grace]로 수동 대사를 실행합니다."""
    wait = last_terminal + RECONCILIATION_GRACE_SECONDS + 1 - time.time()
    if wait > 0:
        print(f"  마지막 종결 후 {RECONCILIATION_GRACE_SECONDS}초가 지나도록 {wait:.1f}초 대기", flush=True)
        time.sleep(wait)
    period_to = iso(datetime.now(timezone.utc) - timedelta(seconds=RECONCILIATION_GRACE_SECONDS))
    started = time.monotonic()
    result = request("POST", f"{RECONCILIATION_URL}?from={first_created_iso}&to={period_to}", MASTER, timeout=300)
    elapsed = round(time.monotonic() - started, 2)
    outcome = {"periodFrom": first_created_iso, "periodTo": period_to, "httpStatus": result.status, "elapsedSeconds": elapsed}
    if result.status != 200:
        outcome["error"] = result.body
        return outcome
    summary = result.data
    outcome["summary"] = summary
    if summary.get("mismatchCount") or summary.get("unverifiableCount"):
        detail = request("GET", f"{RECONCILIATION_URL}/{summary['runId']}?size=100", MASTER)
        if detail.status == 200:
            outcome["results"] = [{key: item.get(key) for key in ("chargeId", "mismatchType", "detail", "sourceType")}
                                  for item in detail.data["results"]["items"]]
        else:
            outcome["resultsError"] = f"{detail} {detail.body}"
    return outcome


# ---- 실행 ----

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("label")
    parser.add_argument("--count", type=int, required=True, help="시작할 충전 건수")
    parser.add_argument("--rate", type=int, required=True, help="초당 시작 건수")
    parser.add_argument("--concurrency", required=True, help="지갑 SAGA 컨슈머 동시성 (결과에 남기는 값)")
    parser.add_argument("--amount", type=int, default=10000)
    parser.add_argument("--pre-vus", type=int, help="기본 max(20, rate)")
    parser.add_argument("--max-vus", type=int, help="기본 max(100, rate × 4)")
    parser.add_argument("--drain-timeout", type=int, default=600, help="k6 종료 후 모든 충전 종결을 기다릴 최대 초")
    args = parser.parse_args()
    if args.count < 1 or args.rate < 1 or args.amount < 1:
        raise SystemExit("--count·--rate·--amount는 1 이상입니다.")
    pre_vus = args.pre_vus or max(20, args.rate)
    max_vus = args.max_vus or max(100, args.rate * 4)

    data = load_data()
    RESULTS.mkdir(exist_ok=True)
    k6_result = RESULTS / f"{args.label}-c{args.concurrency}.json"
    metrics_result = RESULTS / f"{args.label}-c{args.concurrency}-metrics.json"
    for stale in (k6_result, metrics_result):
        stale.unlink(missing_ok=True)   # 앞 실행 결과를 이번 결과로 오인하지 않게

    print("[1/6] 사전 확인 (서비스 health, 처리 중 충전, 측정 계좌)")
    precheck(data)
    balance_before = bank_balance(data)

    print(f"[2/6] k6 실행 (초당 {args.rate}건 × {args.count}건, 동시성 {args.concurrency}), 서비스·DB 지표 1초 간격 기록")
    service_sampler = ServiceSampler()
    db_sampler = DbSampler()
    service_sampler.start()
    db_sampler.start()
    run_started = datetime.now(timezone.utc)
    since = run_started - timedelta(seconds=5)
    env = {"COUNT": args.count, "RATE": args.rate, "PRE_VUS": pre_vus, "MAX_VUS": max_vus, "AMOUNT": args.amount,
           "LABEL": args.label, "CONCURRENCY": args.concurrency}
    command = ["docker", "compose", "run", "--rm"]
    for key, value in env.items():
        command += ["-e", f"{key}={value}"]
    command += ["k6", "run", "--quiet", "/scripts/charge-request.js"]
    k6 = subprocess.run(command, cwd=HERE)
    k6_ended = time.time()
    if k6.returncode != 0 or not k6_result.exists():
        service_sampler.stop.set()
        db_sampler.stop.set()
        raise SystemExit(f"k6 실행 실패(종료 코드 {k6.returncode}). 결과 파일이 없습니다: {k6_result}")
    summary = json.loads(k6_result.read_text(encoding="utf-8"))

    print(f"[3/6] 종결 대기 (최대 {args.drain_timeout}초)")
    drain_seconds = wait_terminal(data, since, args.drain_timeout)
    drain_ended = time.time()
    service_sampler.stop.set()
    db_sampler.stop.set()
    service_sampler.join()
    db_sampler.join(timeout=DbSampler.CHUNK + 60)

    print("[4/6] 지표 정리")
    charges = charge_metrics(data, since)
    window_end = charges.get("lastTerminalEpoch") or drain_ended
    window_start = run_started.timestamp()
    metrics = {
        "label": args.label, "concurrency": args.concurrency, "count": args.count, "rate": args.rate,
        "amount": args.amount, "preAllocatedVUs": pre_vus, "maxVUs": max_vus,
        "window": {"from": iso(run_started), "to": iso(datetime.fromtimestamp(window_end, timezone.utc)),
                   "k6EndedAt": iso(datetime.fromtimestamp(k6_ended, timezone.utc))},
        "accept": {"requests": summary["requests"], "droppedIterations": summary["droppedIterations"],
                   "errorResponses": summary["errorResponses"], "latencyMs": summary["latencyMs"]},
        "drainSeconds": drain_seconds,
        "charges": charges,
        "services": service_metrics(service_sampler.samples, window_start, window_end),
        "db": db_metrics(db_sampler, window_start, window_end),
    }

    reasons = []
    if summary["errorResponses"]:
        reasons.append(f"202 외 응답 {summary['errorResponses']}건 {summary['statusCounts']}")
    if summary["droppedIterations"]:
        reasons.append(f"dropped_iterations {summary['droppedIterations']}건 (--max-vus를 올립니다)")
    manual = charges["statuses"].get("MANUAL_REVIEW", {}).get("count", 0)
    if manual:
        reasons.append(f"MANUAL_REVIEW {manual}건")

    if drain_seconds is None:
        reasons.append(f"{args.drain_timeout}초 안에 종결되지 않은 충전이 있음 (출금 정합성·대사 생략)")
        print("[5/6] 출금 정합성: 종결되지 않은 충전이 있어 생략")
        print("[6/6] 대사: 생략")
    elif charges["accepted"] == 0:
        reasons.append("접수된 충전이 없음")
    else:
        print("[5/6] 출금 정합성 (mock-bank 측정 계좌)")
        metrics["bankConsistency"] = bank_consistency(data, since, charges["statuses"], balance_before)
        reasons += metrics["bankConsistency"]["violations"]
        print("[6/6] 대사 (POST /api/v1/admin/charges/reconciliations)")
        metrics["reconciliation"] = reconcile(charges["firstCreatedAt"], charges["lastTerminalEpoch"])
        run = metrics["reconciliation"].get("summary")
        if not run:
            reasons.append(f"대사 실행 실패 HTTP {metrics['reconciliation']['httpStatus']}")
        elif run["status"] != "COMPLETED" or run["mismatchCount"] or run["unverifiableCount"]:
            reasons.append(f"대사 {run['status']}, 어긋남 {run['mismatchCount']}건, 판정 보류 {run['unverifiableCount']}건")

    metrics["failures"] = reasons
    metrics_result.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    print_brief(metrics)
    if reasons:
        print("\n주의: 이 측정은 원인을 확인하기 전까지 기준값으로 쓰지 않습니다.")
        for reason in reasons:
            print(f"  - {reason}")
        return 1
    return 0


def print_brief(m):
    c, a = m["charges"], m["accept"]
    latency = c.get("completionLatencyMs") or {}
    print("")
    print(f"[{m['label']}] 동시성 {m['concurrency']}, 초당 {m['rate']}건, 목표 {m['count']}건")
    print(f"  접수 {c['accepted']}건(DB) | 접수 P50 {a['latencyMs']['p50']}ms P95 {a['latencyMs']['p95']}ms"
          f" | dropped {a['droppedIterations']} | 202 외 {a['errorResponses']}")
    print(f"  완료 처리량 {c.get('completionTps')}건/초 | 완료 지연 P50 {latency.get('p50')}ms P95 {latency.get('p95')}ms"
          f" | 종결 대기 {m['drainSeconds']}초")
    statuses = ", ".join(f"{status} {entry['count']}" for status, entry in c["statuses"].items())
    print(f"  상태 {statuses}")
    rec = (m.get("reconciliation") or {}).get("summary")
    if rec:
        print(f"  대사 {rec['status']} 대상 {rec['targetCount']} 일치 {rec['matchedCount']} 어긋남 {rec['mismatchCount']}"
              f" 보류 {rec['unverifiableCount']} ({m['reconciliation']['elapsedSeconds']}초)")
    print(f"  결과: results/{m['label']}-c{m['concurrency']}-metrics.json")


if __name__ == "__main__":
    sys.exit(main())
