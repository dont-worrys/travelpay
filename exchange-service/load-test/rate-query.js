// 환율 조회 부하 측정 스크립트입니다. 사용자 수 단계(50/100/300명)마다 한 번씩 실행합니다.
// 실행 방법과 단계별 순서는 README.md를 봅니다.
//
// 흐름: 같은 가상 사용자 VUS명이 예열(WARMUP) + 측정(DURATION) 내내 쉬지 않고 환율 조회를 반복합니다.
// 요청마다 테스트 경과 시간으로 예열/측정을 표시하고(phase 태그), 측정 구간에 시작한 요청만 집계합니다.
// 예열과 측정을 시나리오 두 개로 나누면 k6가 시나리오마다 다른 가상 사용자를 써서, 경계에서
// 예열 요청이 측정 구간으로 넘어와 부하가 겹치거나 새 사용자들이 한꺼번에 연결을 맺어 일부가 거부됐습니다.
// 한 시나리오로 두면 예열 때 맺은 연결을 이어 쓰고, 측정 1분 내내 동시 요청 수가 정확히 VUS개로 유지됩니다.
//
// VUS는 "쉬는 시간 없는 동시 요청 수"입니다. 실제 사용자는 조회 사이에 쉬므로 실제 사용자 수로는 훨씬 많습니다.
// (리틀의 법칙: 동시 사용자 ≈ 처리량 × (응답 시간 + 쉬는 시간). 예: 300 TPS, 쉬는 시간 10초 가정 → 약 3,000명 이상)
//
// 환경 변수
//   VUS      동시 요청 수 = 쉬는 시간 없이 반복하는 가상 사용자 수 (기본 50)
//   DURATION 측정 구간 길이 (기본 1m)
//   WARMUP   예열 구간 길이 (기본 15s)
//   LABEL    결과 파일 이름에 붙일 구분 (예: db, redis)
//   BASE_URL 환전 서비스 주소 (compose에서 같은 Docker 네트워크의 http://app:18084)
//   STALE_AFTER_SECONDS 이보다 오래된 환율 응답을 "오래된 응답"으로 셈 (기본 6 = 수집 간격 5초 + 여유 1초)

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '1m';
const WARMUP = __ENV.WARMUP || '15s';
const LABEL = __ENV.LABEL || 'run';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18084';
const STALE_AFTER_MS = Number(__ENV.STALE_AFTER_SECONDS || 6) * 1000;
const WARMUP_MS = parseSeconds(WARMUP) * 1000;

// 측정 구간에서만 올리는 카운터들
const okResponses = new Counter('ok_responses');          // 200 응답
const clientErrors = new Counter('status_4xx');
const serverErrors = new Counter('status_5xx');
const noResponse = new Counter('no_response');            // 연결 실패·타임아웃 (status 0)
const slowOver1s = new Counter('slow_over_1s');           // 1초 넘게 걸린 요청
const staleResponses = new Counter('stale_rate_responses'); // 수집 시각이 오래된 환율이 들어 있는 응답

export const options = {
    scenarios: {
        // 예열과 측정을 한 시나리오로. 같은 가상 사용자가 끝까지 이어서 요청한다(위 설명)
        rateQuery: {
            executor: 'constant-vus',
            vus: VUS,
            duration: `${parseSeconds(WARMUP) + parseSeconds(DURATION)}s`,
        },
    },
    // 측정 구간만 따로 집계하려고 phase:measure 하위 지표를 만든다. 기준(>=0)은 항상 통과하는 값이라 측정을 멈추지 않는다.
    thresholds: {
        'http_req_duration{phase:measure}': ['avg>=0'],
        'http_req_failed{phase:measure}': ['rate>=0'],
        'http_reqs{phase:measure}': ['count>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    // 요청을 "시작한" 시각으로 예열/측정을 정한다. 기준은 k6 엔진의 테스트 경과 시간 하나라 모든 가상 사용자가 같다
    const phase = exec.instance.currentTestRunDuration < WARMUP_MS ? 'warmup' : 'measure';
    const response = http.get(`${BASE_URL}/api/v1/exchange-rates`, { tags: { phase } });
    if (phase !== 'measure') {
        return;
    }
    count(response);
}

function count(response) {
    if (response.status === 200) {
        okResponses.add(1);
        countStale(response);
    } else if (response.status === 0) {
        noResponse.add(1);
    } else if (response.status >= 500) {
        serverErrors.add(1);
    } else if (response.status >= 400) {
        clientErrors.add(1);
    }
    if (response.timings.duration > 1000) {
        slowOver1s.add(1);
    }
}

/** 응답의 통화별 환율 중 하나라도 수집 시각이 STALE_AFTER보다 오래됐으면 오래된 응답으로 센다 */
function countStale(response) {
    const rates = response.json('data');
    if (!Array.isArray(rates)) {
        return;
    }
    const now = Date.now();
    const stale = rates.some((rate) => now - Date.parse(rate.collectedAt) > STALE_AFTER_MS);
    if (stale) {
        staleResponses.add(1);
    }
}

/** 측정 구간 지표만 골라 화면에 표로 찍고, results/ 폴더에 JSON으로 남긴다 */
export function handleSummary(data) {
    const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
    const duration = metric('http_req_duration{phase:measure}');
    const measureSeconds = parseSeconds(DURATION);
    const ok = metric('ok_responses').count || 0;
    const total = metric('http_reqs{phase:measure}').count || 0;
    const result = {
        label: LABEL,
        vus: VUS,
        measureSeconds,
        warmup: WARMUP,
        requests: total,
        // 예열 포함 전체 요청 수. pg_stat_statements(예열 포함) 검산에 씀
        requestsAll: metric('http_reqs').count || 0,
        // k6가 실제로 테스트를 돈 시간. measure.sh가 컨테이너 시작 지연과 상관없이 측정 구간을 계산하는 데 씀
        testRunDurationMs: Math.round(data.state.testRunDurationMs),
        latencyMs: {
            avg: round(duration.avg),
            p50: round(duration.med),
            p95: round(duration['p(95)']),
            p99: round(duration['p(99)']),
            max: round(duration.max),
        },
        totalTps: round(total / measureSeconds),
        successTps: round(ok / measureSeconds),
        failureRate: round((metric('http_req_failed{phase:measure}').rate || 0) * 100, 3),
        statusCounts: {
            ok,
            '4xx': metric('status_4xx').count || 0,
            '5xx': metric('status_5xx').count || 0,
            noResponse: metric('no_response').count || 0,
        },
        slowOver1s: metric('slow_over_1s').count || 0,
        staleResponses: metric('stale_rate_responses').count || 0,
    };
    const file = `/results/${LABEL}-vus${VUS}.json`;
    return {
        stdout: table(result),
        [file]: JSON.stringify(result, null, 2),
    };
}

function table(r) {
    return [
        '',
        `[${r.label}] 동시 요청 ${r.vus}개, 측정 ${r.measureSeconds}초 (예열 ${r.warmup} 제외)`,
        `  요청 ${r.requests}건 | 성공 TPS ${r.successTps} | 실패율 ${r.failureRate}%`,
        `  응답(ms) 평균 ${r.latencyMs.avg} | P50 ${r.latencyMs.p50} | P95 ${r.latencyMs.p95} | P99 ${r.latencyMs.p99} | 최대 ${r.latencyMs.max}`,
        `  상태 200 ${r.statusCounts.ok} | 4xx ${r.statusCounts['4xx']} | 5xx ${r.statusCounts['5xx']} | 응답 없음 ${r.statusCounts.noResponse}`,
        `  1초 초과 ${r.slowOver1s} | 오래된 환율 응답 ${r.staleResponses}`,
        '',
    ].join('\n');
}

function parseSeconds(value) {
    const match = /^(\d+)(ms|s|m)$/.exec(value);
    if (!match) {
        throw new Error(`DURATION 형식이 올바르지 않습니다: ${value}`);
    }
    const amount = Number(match[1]);
    return { ms: amount / 1000, s: amount, m: amount * 60 }[match[2]];
}

function round(value, digits = 2) {
    if (value === undefined || Number.isNaN(value)) {
        return null;
    }
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
}
