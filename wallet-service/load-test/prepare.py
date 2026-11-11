"""충전 부하 측정용 회원·지갑·mock-bank 계좌를 준비하고 data/users.json에 남깁니다.

로컬 전체 스택(user·wallet·ledger, Postgres)이 떠 있어야 합니다. 지갑 개설에 wallet의 LEDGER_SERVICE_URL이 필요합니다.
- 회원 USERS명: 실명 HOLDER(기본 유재석)로 가입 → 지갑 개설
- 회원마다 서로 다른 mock-bank 계좌를 DB에 직접 INSERT (예금주 HOLDER, 은행 BANK_CODES, 잔액 BANK_BALANCE)
  은행이 여러 개면 회원 순번대로 돌아가며 배정합니다(004,088이면 u1=004, u2=088, u3=004 …).
  k6도 회원을 순번대로 쓰므로 회원 수가 은행 수의 배수면 충전이 은행별로 고르게 나뉩니다.
  계좌번호는 77 + 실행 시각(UTC yyMMddHHmmss) + 순번 4자리(18자리)라 실행끼리 겹치지 않습니다. Flyway 시드 계좌는 건드리지 않습니다.
충전은 하지 않습니다(충전 건수는 measure.py --count).

사용: python prepare.py [--users 50] [--holder 유재석] [--bank-codes 004[,088]] [--bank-balance 1000000000000]
"""
import argparse
import http.cookiejar
import json
import random
import re
import sys
import urllib.request
from datetime import datetime, timezone

from lt_common import DATA_FILE, USER_URL, WALLET_URL, member_headers, psql, request, sql_text

PASSWORD = "TravelpayLoad-2026!"   # 로컬 측정 전용 회원. 로그인하지 않으므로 다른 곳에 쓰지 않음
CONSENTS = [{"termsCode": "SERVICE_TERMS", "termsVersion": "2026-10-01"},
            {"termsCode": "PRIVACY_TERMS", "termsVersion": "2026-10-01"}]
PHONE_RETRIES = 5   # 무작위 전화번호가 기존 회원과 겹치면(USER_PHONE_ALREADY_EXISTS) 새 번호로 다시 시도하는 횟수


def signup(run_id, index, real_name):
    """CSRF 토큰을 받아 회원가입하고 회원 ID를 돌려줍니다. 가입 즉시 ACTIVE입니다."""
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = request("GET", f"{USER_URL}/api/v1/auth/csrf", opener=opener)
    if csrf.status != 200:
        raise RuntimeError(f"CSRF 토큰 조회 실패: {csrf}")
    for retry in range(PHONE_RETRIES + 1):
        phone = f"010-{random.randint(2000, 9999)}-{random.randint(1000, 9999)}"   # phone은 UNIQUE
        body = {"loginEmail": f"lt-charge-{run_id}-u{index}@example.com", "password": PASSWORD,
                "realName": real_name, "phone": phone, "consents": CONSENTS}
        result = request("POST", f"{USER_URL}/api/v1/users",
                         {csrf.data["headerName"]: csrf.data["token"]}, body, opener=opener)
        if result.status == 201:
            return result.data["userId"]
        if result.code != "USER_PHONE_ALREADY_EXISTS" or retry == PHONE_RETRIES:
            raise RuntimeError(f"회원가입 실패 u{index}: {result} {result.body}")
        print(f"  u{index} 전화번호 {phone} 중복, 새 번호로 재시도 ({retry + 1}/{PHONE_RETRIES})", flush=True)


def open_wallet(user_id):
    result = request("POST", f"{WALLET_URL}/api/v1/wallets", member_headers(user_id))
    if result.status not in (200, 201) or result.data["status"] != "ACTIVE":
        raise RuntimeError(f"지갑 개설 실패 userId={user_id}: {result} {result.body}")
    return result.data["walletId"]


def insert_bank_accounts(users, holder, balance):
    """측정 회원 계좌(회원별 은행·계좌)를 한 문장으로 INSERT합니다. 계좌가 겹치면 UNIQUE 위반으로 전체가 실패합니다."""
    values = ",\n".join(
        f"(gen_random_uuid(), {sql_text(user['bankCode'])}, {sql_text(user['accountNumber'])}, {sql_text(holder)},"
        f" 'USER', {balance}, 'ACTIVE')" for user in users)
    psql("INSERT INTO mock_bank.bank_accounts"
         " (id, bank_code, account_number, holder_name, owner_type, balance, status)\n"
         f"VALUES {values};\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--users", type=int, default=50)
    parser.add_argument("--holder", default="유재석")
    parser.add_argument("--bank-codes", default="004",
                        help="쉼표로 구분한 은행 코드(ASCII 숫자 3자리, 지갑 충전 검증과 같음). 회원 순번대로 돌아가며 배정. 예: 004,088")
    parser.add_argument("--bank-balance", type=int, default=1_000_000_000_000)
    args = parser.parse_args()
    bank_codes = [code.strip() for code in args.bank_codes.split(",")]
    # \d는 전각·아랍 숫자 등 유니코드 숫자도 받으므로 ASCII만 허용(ChargeCreateRequest의 ^[0-9]{3}$와 같게)
    if not all(re.fullmatch(r"[0-9]{3}", code) for code in bank_codes) or len(set(bank_codes)) != len(bank_codes):
        raise SystemExit("--bank-codes는 겹치지 않는 ASCII 숫자(0-9) 3자리 은행 코드를 쉼표로 구분합니다. 예: 004,088")
    if not 1 <= args.users <= 9999 or args.bank_balance < 0:
        raise SystemExit("--users는 1~9999, --bank-balance는 0 이상입니다.")

    now = datetime.now(timezone.utc)
    run_id = now.strftime("%Y%m%d%H%M%S")
    account_prefix = "77" + now.strftime("%y%m%d%H%M%S")
    print(f"[1/3] 회원·지갑 준비: {args.users}명 (실명 {args.holder})")
    users = []
    for index in range(1, args.users + 1):
        user_id = signup(run_id, index, args.holder)
        open_wallet(user_id)
        users.append({"userId": user_id, "bankCode": bank_codes[(index - 1) % len(bank_codes)],
                      "accountNumber": f"{account_prefix}{index:04d}"})
        if index % 10 == 0 or index == args.users:
            print(f"  {index}/{args.users}", flush=True)

    per_bank = ", ".join(f"{code} {sum(1 for user in users if user['bankCode'] == code)}명" for code in bank_codes)
    print(f"[2/3] mock-bank 계좌 INSERT: 은행 {per_bank}, 예금주 {args.holder}, 잔액 {args.bank_balance:,}원 × {args.users}")
    insert_bank_accounts(users, args.holder, args.bank_balance)

    print("[3/3] 저장")
    DATA_FILE.parent.mkdir(exist_ok=True)
    DATA_FILE.write_text(json.dumps({
        "preparedAt": now.isoformat(),
        "bankCodes": bank_codes,
        "holder": args.holder,
        "bankBalance": args.bank_balance,
        "users": users,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"완료: {DATA_FILE}")


if __name__ == "__main__":
    sys.exit(main())
