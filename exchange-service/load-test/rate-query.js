// 환율 조회(GET /api/v1/exchange-rates) 부하 측정 스크립트입니다. 동시 요청 수 단계(50/100/300)마다 한 번씩 실행합니다.
// 실행 방법과 단계별 순서는 README.md, 측정 방식과 공통 환경 변수는 k6-common.js를 봅니다.
//
// VUS는 "쉬는 시간 없는 동시 요청 수"입니다. 실제 사용자는 조회 사이에 쉬므로 실제 사용자 수로는 훨씬 많습니다.
// (리틀의 법칙: 동시 사용자 ≈ 처리량 × (응답 시간 + 쉬는 시간). 쉬는 시간을 가정한 어림값)
//
// 이 스크립트만의 환경 변수
//   STALE_AFTER_SECONDS 이보다 오래된 환율 응답을 "오래된 응답"으로 셈 (기본 6 = 수집 간격 5초 + 여유 1초)

import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { BASE_URL, count, currentPhase, summarize } from './k6-common.js';

export { options } from './k6-common.js';

const STALE_AFTER_MS = Number(__ENV.STALE_AFTER_SECONDS || 6) * 1000;
const staleResponses = new Counter('stale_rate_responses'); // 수집 시각이 오래된 환율이 들어 있는 응답 (측정 구간)

export default function () {
    const phase = currentPhase();
    const response = http.get(`${BASE_URL}/api/v1/exchange-rates`, { tags: { phase } });
    if (count(response, phase, 200)) {
        countStale(response);
    }
}

/** 응답의 통화별 환율 중 하나라도 수집 시각이 STALE_AFTER보다 오래됐으면 오래된 응답으로 센다 */
function countStale(response) {
    const rates = response.json('data');
    if (!Array.isArray(rates)) {
        return;
    }
    const now = Date.now();
    if (rates.some((rate) => now - Date.parse(rate.collectedAt) > STALE_AFTER_MS)) {
        staleResponses.add(1);
    }
}

export function handleSummary(data) {
    const stale = data.metrics.stale_rate_responses;
    return summarize(data, '환율 조회', { staleResponses: stale ? stale.values.count : 0 });
}
