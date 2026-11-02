// 환율 조회 부하 측정 스크립트입니다. 사용자 수 단계(50/100/300명)마다 한 번씩 실행합니다.
// 실행 방법과 단계별 순서는 README.md를 봅니다.
//
// 흐름: 예열(WARMUP) 동안 같은 사용자 수로 요청만 보내고 집계에서 뺀 뒤, 측정(DURATION) 구간만 집계합니다.
// 각 가상 사용자는 쉬지 않고 환율 조회를 반복합니다(요청 사이 대기 없음). 서버가 받아낼 수 있는 최대 처리량을 봅니다.
//
// 환경 변수
//   VUS      동시 사용자 수 (기본 50)
//   DURATION 측정 구간 길이 (기본 1m)
//   WARMUP   예열 구간 길이 (기본 15s)
//   LABEL    결과 파일 이름에 붙일 구분 (예: db, redis)
//   BASE_URL 환전 서비스 주소 (compose에서 http://host.docker.internal:18084)
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

// 측정 구간에서만 올리는 카운터들
const okResponses = new Counter('ok_responses');          // 200 응답
const clientErrors = new Counter('status_4xx');
const serverErrors = new Counter('status_5xx');
const noResponse = new Counter('no_response');            // 연결 실패·타임아웃 (status 0)
const slowOver1s = new Counter('slow_over_1s');           // 1초 넘게 걸린 요청
const staleResponses = new Counter('stale_rate_responses'); // 수집 시각이 오래된 환율이 들어 있는 응답

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: VUS,
            duration: WARMUP,
            tags: { phase: 'warmup' },
        },
        measure: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
            startTime: WARMUP,
            tags: { phase: 'measure' },
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
    const response = http.get(`${BASE_URL}/api/v1/exchange-rates`);
    if (exec.scenario.name !== 'measure') {
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
        `[${r.label}] 동시 사용자 ${r.vus}명, 측정 ${r.measureSeconds}초 (예열 ${r.warmup} 제외)`,
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
