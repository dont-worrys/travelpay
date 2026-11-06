"""송금 부하 측정용 회원·지갑·잔액을 준비하고 data/users.json에 남깁니다.

로컬 전체 스택(user·wallet·ledger·payment, Kafka, mock-bank)이 떠 있어야 합니다. wallet은 MOCK_BANK_URL을 넣고 띄웁니다.
- 송금인 PAIRS명: 실명을 mock-bank 시드 계좌 예금주(기본 유재석)로 가입 → 지갑 개설 → 충전(CHARGE원)
- 수취인 PAIRS명 + 집중(hot) 수취인 1명: 가입 → 지갑 개설 (충전 없음)
충전은 모두 접수한 뒤 COMPLETED가 될 때까지 기다립니다. 하나라도 FAILED·MANUAL_REVIEW면 멈춥니다.
mock-bank 계좌 잔액은 DB에 남아 반복하면 줄어듭니다(유재석 004 계좌 시작 3,000,000원).

사용: python -I prepare.py [--pairs 20] [--charge 20000] [--bank-code 004] [--account 12340101234567] [--holder 유재석]
"""
import argparse
import http.cookiejar
import json
import random
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timezone

from lt_common import DATA_FILE, USER_URL, WALLET_URL, krw_balance, member_headers, request

PASSWORD = "TravelpayLoad-2026!"   # 로컬 측정 전용 회원. 로그인하지 않으므로 다른 곳에 쓰지 않음
CONSENTS = [{"termsCode": "SERVICE_TERMS", "termsVersion": "2026-10-01"},
            {"termsCode": "PRIVACY_TERMS", "termsVersion": "2026-10-01"}]


def signup(run_id, role, index, real_name):
    """CSRF 토큰을 받아 회원가입하고 회원 ID를 돌려줍니다. 가입 즉시 ACTIVE입니다."""
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = request("GET", f"{USER_URL}/api/v1/auth/csrf", opener=opener)
    if csrf.status != 200:
        raise RuntimeError(f"CSRF 토큰 조회 실패: {csrf}")
    phone = f"010-{random.randint(2000, 9999)}-{random.randint(1000, 9999)}"   # phone은 UNIQUE
    body = {"loginEmail": f"lt-{run_id}-{role}{index}@example.com", "password": PASSWORD,
            "realName": real_name, "phone": phone, "consents": CONSENTS}
    result = request("POST", f"{USER_URL}/api/v1/users",
                     {csrf.data["headerName"]: csrf.data["token"]}, body, opener=opener)
    if result.status != 201:
        raise RuntimeError(f"회원가입 실패 {role}{index}: {result} {result.body}")
    return result.data["userId"]


def open_wallet(user_id):
    result = request("POST", f"{WALLET_URL}/api/v1/wallets", member_headers(user_id))
    if result.status not in (200, 201) or result.data["status"] != "ACTIVE":
        raise RuntimeError(f"지갑 개설 실패 userId={user_id}: {result} {result.body}")
    return result.data["walletId"]


def request_charge(user_id, amount, bank_code, account):
    result = request("POST", f"{WALLET_URL}/api/v1/charges",
                     {**member_headers(user_id), "Idempotency-Key": str(uuid.uuid4())},
                     {"amount": amount, "currency": "KRW", "bankCode": bank_code, "accountNumber": account})
    if result.status not in (200, 202):
        raise RuntimeError(f"충전 요청 실패 userId={user_id}: {result} {result.body}")
    return result.data["chargeId"]


def wait_charges(charges, timeout_seconds):
    """모든 충전이 COMPLETED가 될 때까지 기다립니다. 실패·수동 검토가 있거나 시간이 지나면 멈춥니다."""
    deadline = time.monotonic() + timeout_seconds
    pending = dict(charges)
    while pending:
        for user_id, charge_id in list(pending.items()):
            result = request("GET", f"{WALLET_URL}/api/v1/charges/{charge_id}", member_headers(user_id))
            status = (result.data or {}).get("status")
            if status == "COMPLETED":
                del pending[user_id]
            elif status in ("FAILED", "MANUAL_REVIEW", "REFUNDED"):
                raise SystemExit(f"충전이 {status}로 끝났습니다 chargeId={charge_id}. mock-bank·MOCK_BANK_URL을 확인하세요.")
        print(f"  충전 완료 {len(charges) - len(pending)}/{len(charges)}", flush=True)
        if pending and time.monotonic() > deadline:
            raise SystemExit(f"{timeout_seconds}초 안에 충전이 끝나지 않았습니다: {list(pending.values())}")
        if pending:
            time.sleep(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pairs", type=int, default=20)
    parser.add_argument("--charge", type=int, default=20000)
    parser.add_argument("--bank-code", default="004")
    parser.add_argument("--account", default="12340101234567")
    parser.add_argument("--holder", default="유재석")
    parser.add_argument("--charge-timeout", type=int, default=1200)
    args = parser.parse_args()

    run_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    print(f"[1/3] 회원·지갑 준비: 송금인 {args.pairs}명({args.holder}), 수취인 {args.pairs}명 + 집중 수취인 1명")
    pairs = []
    for index in range(1, args.pairs + 1):
        sender = signup(run_id, "s", index, args.holder)
        receiver = signup(run_id, "r", index, "부하수취인")
        open_wallet(sender)
        open_wallet(receiver)
        pairs.append({"senderId": sender, "receiverId": receiver})
    hot_receiver = signup(run_id, "h", 0, "집중수취인")
    open_wallet(hot_receiver)

    print(f"[2/3] 충전 접수: 송금인마다 {args.charge:,}원 (계좌 {args.bank_code}/{args.account})")
    charges = {pair["senderId"]: request_charge(pair["senderId"], args.charge, args.bank_code, args.account)
               for pair in pairs}
    wait_charges(charges, args.charge_timeout)

    print("[3/3] 잔액 확인")
    for pair in pairs:
        balance = krw_balance(pair["senderId"])
        if balance < args.charge:
            raise SystemExit(f"송금인 잔액이 충전액보다 적습니다 {pair['senderId']}: {balance}")
    DATA_FILE.parent.mkdir(exist_ok=True)
    DATA_FILE.write_text(json.dumps({
        "preparedAt": datetime.now(timezone.utc).isoformat(),
        "chargeAmount": args.charge,
        "pairs": pairs,
        "hotReceiverId": hot_receiver,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"완료: {DATA_FILE}")


if __name__ == "__main__":
    sys.exit(main())
