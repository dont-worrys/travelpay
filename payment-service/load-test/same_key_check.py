"""같은 Idempotency-Key 동시 요청이 실제 원장에서도 한 번만 차감되는지 확인합니다 (로컬 전체 스택, Gateway 제외).

라운드마다 송금인 한 명이 같은 키로 THREADS건을 동시에(배리어로 맞춰) 보냅니다.
- same    : 모두 같은 본문 → 송금 1건, 모든 응답이 같은 transferId(201 또는 처리 중 202), 오류 없음
- conflict: 절반은 금액 A, 절반은 금액 B → 송금 1건, 이긴 쪽과 같은 본문은 같은 transferId, 다른 본문은 409
라운드가 끝나면 PROCESSING이 없어질 때까지 기다린 뒤 송금인·수취인 잔액 변화가 완료 송금 합계와 같은지,
송금인의 보낸 송금 수가 라운드 수만큼만 늘었는지 확인합니다.

사용: python -X utf8 same_key_check.py [--rounds 20] [--threads 20] [--pair 0]
"""
import argparse
import json
import sys
import threading
import time
import uuid
from collections import Counter

from lt_common import DATA_FILE, PAYMENT_URL, krw_balance, member_headers, request


def sent_count(user_id):
    result = request("GET", f"{PAYMENT_URL}/api/v1/transfers?direction=SENT&size=1", member_headers(user_id))
    return result.data["totalElements"]


def fire(sender, receiver, key, amounts):
    """amounts[i] 금액으로 같은 키 요청을 동시에 보내고 (금액, 상태, transferId, 오류 코드) 목록을 돌려줍니다."""
    barrier = threading.Barrier(len(amounts))
    results = [None] * len(amounts)

    def call(index):
        barrier.wait()
        result = request("POST", f"{PAYMENT_URL}/api/v1/transfers",
                         {**member_headers(sender), "Idempotency-Key": key},
                         {"receiverId": receiver, "amount": amounts[index], "currency": "KRW"})
        results[index] = (amounts[index], result.status, (result.data or {}).get("transferId"), result.code)

    threads = [threading.Thread(target=call, args=(i,)) for i in range(len(amounts))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def check_round(kind, results):
    """라운드 결과의 위반 목록과 이긴 (transferId, 금액)을 돌려줍니다."""
    violations = []
    ids = {transfer_id for _, status, transfer_id, _ in results if status in (201, 202)}
    if len(ids) != 1:
        violations.append(f"{kind}: 성공 응답의 transferId가 {len(ids)}개 {ids}")
        return violations, None
    winner_id = ids.pop()
    winner_amounts = {amount for amount, status, _, _ in results if status in (201, 202)}
    if len(winner_amounts) != 1:
        violations.append(f"{kind}: 서로 다른 금액이 모두 성공 {winner_amounts}")
    winner_amount = next(iter(winner_amounts))
    for amount, status, transfer_id, code in results:
        if amount == winner_amount and status not in (201, 202):
            violations.append(f"{kind}: 같은 본문인데 {status} {code}")
        if amount != winner_amount and (status, code) != (409, "TRANSFER_IDEMPOTENCY_KEY_REUSED"):
            violations.append(f"{kind}: 다른 본문인데 {status} {code}")
    return violations, (winner_id, winner_amount)


def wait_settled(sender, transfer_ids, timeout_seconds=600):
    deadline = time.monotonic() + timeout_seconds
    while True:
        statuses = {tid: request("GET", f"{PAYMENT_URL}/api/v1/transfers/{tid}", member_headers(sender)).data["status"]
                    for tid in transfer_ids}
        if all(status != "PROCESSING" for status in statuses.values()) or time.monotonic() > deadline:
            return statuses
        time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--threads", type=int, default=20)
    parser.add_argument("--pair", type=int, default=0, help="data/users.json의 몇 번째 쌍을 쓸지")
    args = parser.parse_args()

    pair = json.loads(DATA_FILE.read_text(encoding="utf-8"))["pairs"][args.pair]
    sender, receiver = pair["senderId"], pair["receiverId"]
    before = {"sender": krw_balance(sender), "receiver": krw_balance(receiver), "sent": sent_count(sender)}

    violations, winners, status_counts = [], {}, Counter()
    for round_index in range(args.rounds):
        kind = "same" if round_index % 2 == 0 else "conflict"
        half = args.threads // 2
        amounts = [7] * args.threads if kind == "same" else [7] * half + [11] * (args.threads - half)
        results = fire(sender, receiver, f"samekey-{uuid.uuid4()}", amounts)
        status_counts.update(f"{kind}:{status}" for _, status, _, _ in results)
        round_violations, winner = check_round(kind, results)
        violations += [f"라운드 {round_index + 1} {v}" for v in round_violations]
        if winner:
            winners[winner[0]] = winner[1]

    statuses = wait_settled(sender, winners)
    completed = sum(amount for tid, amount in winners.items() if statuses[tid] == "COMPLETED")
    after = {"sender": krw_balance(sender), "receiver": krw_balance(receiver), "sent": sent_count(sender)}
    if after["sent"] - before["sent"] != args.rounds:
        violations.append(f"보낸 송금 수 증가 {after['sent'] - before['sent']} (기대 {args.rounds})")
    if before["sender"] - after["sender"] != completed:
        violations.append(f"송금인 차감 {before['sender'] - after['sender']} (완료 송금 합계 {completed})")
    if after["receiver"] - before["receiver"] != completed:
        violations.append(f"수취인 증가 {after['receiver'] - before['receiver']} (완료 송금 합계 {completed})")

    report = {
        "rounds": args.rounds, "threads": args.threads, "requests": args.rounds * args.threads,
        "responses": dict(sorted(status_counts.items())),
        "transferStatuses": dict(Counter(statuses.values())),
        "balance": {"before": before, "after": after, "completedAmount": completed},
        "violations": violations,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
