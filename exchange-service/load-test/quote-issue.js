// 견적 발급(POST /api/v1/exchange-quotes) 부하 측정 스크립트입니다. 동시 요청 수 단계(50/100/300)마다 한 번씩 실행합니다.
// 실행 방법은 README.md, 측정 방식과 공통 환경 변수는 k6-common.js를 봅니다.
//
// 견적 발급은 최신 환율 읽기 → 견적 저장을 합니다. jar에 따라 저장소가 다릅니다.
//   v0: 환율 DB 읽기 + 견적 DB INSERT·커밋 / quote-db(#223 직전): 환율 Redis + 견적 DB INSERT·커밋
//   quote-redis(#223 이후): 환율 Redis + 견적 Redis SET (발급에 DB 연결을 쓰지 않음)
// 지갑·원장을 부르지 않아 User-Id·User-Role 헤더만으로 요청할 수 있습니다(둘 중 하나라도 없으면 401).
// 가상 사용자마다 다른 회원 ID를 써서, 실제처럼 여러 회원이 각자 견적을 받는 상황으로 둡니다.
//
// 이 스크립트만의 환경 변수
//   QUOTE_AMOUNT 보내는 원화 금액 (기본 100000, 1회 한도 1,000 ~ 2,000,000 안)

import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, count, currentPhase, summarize } from './k6-common.js';

export { options } from './k6-common.js';

const BODY = JSON.stringify({ fromCurrency: 'KRW', toCurrency: 'JPY', amount: __ENV.QUOTE_AMOUNT || '100000' });

export default function () {
    const phase = currentPhase();
    const response = http.post(`${BASE_URL}/api/v1/exchange-quotes`, BODY, {
        headers: {
            'Content-Type': 'application/json',
            'User-Id': userIdOf(exec.vu.idInTest),
            'User-Role': 'USER',
        },
        tags: { phase },
    });
    count(response, phase, 201);
}

/** 가상 사용자 번호로 고정 UUID를 만든다 (1번 → 00000000-0000-4000-8000-000000000001). 실행마다 같은 회원 */
function userIdOf(vu) {
    return `00000000-0000-4000-8000-${String(vu).padStart(12, '0')}`;
}

export function handleSummary(data) {
    return summarize(data, '견적 발급');
}
