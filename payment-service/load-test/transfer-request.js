// 송금 요청(POST /api/v1/transfers) 부하 측정 스크립트입니다. 실행 방법은 README.md, 공통 설정은 k6-common.js를 봅니다.
//
// 송금 요청 한 건은 지갑 조회 2번(송금인·수취인) + 회원 확인 1번(I02) + 송금 저장 + 원장 거래 등록 + 결과 반영을 합니다.
// 서비스는 로컬 전체 스택(PC)을 그대로 쓰고, payment-service를 User-Id·User-Role 헤더로 직접 호출합니다(Gateway 제외).
// 회원·지갑·잔액은 prepare.py가 미리 만들어 data/users.json에 남깁니다.
//
// 이 스크립트만의 환경 변수
//   MODE   spread(기본): 가상 사용자마다 서로 다른 송금인→수취인 쌍. 원장 계정 잠금이 겹치지 않음
//          hot          : 모든 송금인이 수취인 한 명에게 보냄. 수취인 원장 계정 잠금이 한곳에 몰림
//   AMOUNT 송금 금액(원, 기본 1). 측정 중 잔액이 바닥나지 않게 작게 둠
//
// 성공은 201(COMPLETED)입니다. 202(원장 결과 모름 → PROCESSING)와 422(잔액 부족 등)는 따로 세고,
// 실패율(http_req_failed)에서는 빼고 결과 JSON의 오류율(429·기타 4xx·5xx·응답 없음)로 판단합니다.

import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, LABEL, count, currentPhase, summarize } from './k6-common.js';

export { options } from './k6-common.js';

const MODE = __ENV.MODE || 'spread';
const AMOUNT = Number(__ENV.AMOUNT || 1);
const DATA = JSON.parse(open('./data/users.json'));

http.setResponseCallback(http.expectedStatuses(201, 202, 422));

export function setup() {
    if (!['spread', 'hot'].includes(MODE)) {
        throw new Error(`MODE는 spread 또는 hot입니다: ${MODE}`);
    }
    if (MODE === 'spread' && DATA.pairs.length < Number(__ENV.VUS || 20)) {
        // 송금인이 겹치면 spread가 아니므로 막는다(송금인 계정 잠금이 겹침)
        throw new Error(`spread는 VUS(${__ENV.VUS}) 이하의 송금인이 필요합니다. 준비된 쌍: ${DATA.pairs.length}`);
    }
    // 실행마다 다른 멱등성 키 접두사 (같은 키 재요청이 섞이지 않게)
    return { runId: `${LABEL}-${Date.now()}` };
}

export default function (setupData) {
    const vu = exec.vu.idInTest;
    const pair = DATA.pairs[(vu - 1) % DATA.pairs.length];
    const receiverId = MODE === 'hot' ? DATA.hotReceiverId : pair.receiverId;
    const phase = currentPhase();
    const response = http.post(`${BASE_URL}/api/v1/transfers`,
        JSON.stringify({ receiverId, amount: AMOUNT, currency: 'KRW' }), {
            headers: {
                'Content-Type': 'application/json',
                'User-Id': pair.senderId,
                'User-Role': 'USER',
                // 공백 없는 ASCII 1~100자
                'Idempotency-Key': `${setupData.runId}-${vu}-${exec.vu.iterationInScenario}`,
            },
            tags: { phase, name: 'POST /api/v1/transfers' },
        });
    count(response, phase, response.status === 201);
}

export function handleSummary(data) {
    return summarize(data, `송금 요청(${MODE})`, { mode: MODE, amount: AMOUNT });
}
