// 부하 측정 스크립트(rate-query.js, quote-issue.js)가 함께 쓰는 설정과 결과 정리입니다.
//
// 측정 방식 (두 스크립트 공통): 같은 가상 사용자 VUS명이 예열(WARMUP) + 측정(DURATION) 내내 쉬지 않고 요청합니다.
// 요청마다 테스트 경과 시간으로 예열/측정을 표시하고(phase 태그), 측정 구간에 시작한 요청만 집계합니다.
// 예열과 측정을 시나리오 두 개로 나누면 k6가 시나리오마다 다른 가상 사용자를 써서, 경계에서
// 예열 요청이 측정 구간으로 넘어와 부하가 겹치거나 새 사용자들이 한꺼번에 연결을 맺어 일부가 거부됐습니다.
// 한 시나리오로 두면 예열 때 맺은 연결을 이어 쓰고, 측정 1분 내내 동시 요청 수가 정확히 VUS개로 유지됩니다.
//
// 공통 환경 변수
//   VUS      동시 요청 수 = 쉬는 시간 없이 반복하는 가상 사용자 수 (기본 50)
//   DURATION 측정 구간 길이 (기본 1m)
//   WARMUP   예열 구간 길이 (기본 15s)
//   LABEL    결과 파일 이름에 붙일 구분 (예: db, redis)
//   BASE_URL 환전 서비스 주소 (compose에서 같은 Docker 네트워크의 http://app:18084)

import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

export const VUS = Number(__ENV.VUS || 50);
export const DURATION = __ENV.DURATION || '1m';
export const WARMUP = __ENV.WARMUP || '15s';
export const LABEL = __ENV.LABEL || 'run';
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:18084';
const WARMUP_MS = parseSeconds(WARMUP) * 1000;

// 측정 구간에서만 올리는 카운터들
const okResponses = new Counter('ok_responses');          // 기대한 성공 상태 응답
const okAll = new Counter('ok_responses_all');            // 예열 포함 성공 응답 (DB 저장 건수 검산용)
const clientErrors = new Counter('status_4xx');
const serverErrors = new Counter('status_5xx');
const noResponse = new Counter('no_response');            // 연결 실패·타임아웃 (status 0)
const slowOver1s = new Counter('slow_over_1s');           // 1초 넘게 걸린 요청

/** 예열과 측정을 한 시나리오로 둔 k6 옵션. 측정 구간만 따로 집계하려고 phase:measure 하위 지표를 만든다 */
export const options = {
    scenarios: {
        main: {
            executor: 'constant-vus',
            vus: VUS,
            duration: `${parseSeconds(WARMUP) + parseSeconds(DURATION)}s`,
        },
    },
    // 기준(>=0)은 항상 통과하는 값이라 측정을 멈추지 않는다. 실패 판단은 measure-all.sh가 결과 JSON으로 한다
    thresholds: {
        'http_req_duration{phase:measure}': ['avg>=0'],
        'http_req_failed{phase:measure}': ['rate>=0'],
        'http_reqs{phase:measure}': ['count>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

/** 요청을 "시작한" 시각으로 예열/측정을 정한다. 기준은 k6 엔진의 테스트 경과 시간 하나라 모든 가상 사용자가 같다 */
export function currentPhase() {
    return exec.instance.currentTestRunDuration < WARMUP_MS ? 'warmup' : 'measure';
}

/**
 * 응답 하나를 센다. 성공(okStatus)은 예열 포함으로도 세고, 나머지 지표는 측정 구간에서만 센다.
 *
 * @returns 측정 구간의 성공 응답이면 true (스크립트별 추가 검사에 씀)
 */
export function count(response, phase, okStatus) {
    const ok = response.status === okStatus;
    if (ok) {
        okAll.add(1);
    }
    if (phase !== 'measure') {
        return false;
    }
    if (ok) {
        okResponses.add(1);
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
    return ok;
}

/**
 * 측정 구간 지표만 골라 화면에 표로 찍고, results/ 폴더에 JSON으로 남긴다.
 *
 * @param extra 스크립트별로 결과에 더할 값 (예: { staleResponses })
 */
export function summarize(data, title, extra = {}) {
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
        // 예열 포함 전체 요청 수와 성공 수. pg_stat_statements·DB 저장 건수(예열 포함) 검산에 씀
        requestsAll: metric('http_reqs').count || 0,
        okAll: metric('ok_responses_all').count || 0,
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
        ...extra,
    };
    return {
        stdout: table(result, title),
        [`/results/${LABEL}-vus${VUS}.json`]: JSON.stringify(result, null, 2),
    };
}

function table(r, title) {
    return [
        '',
        `[${r.label}] ${title}, 동시 요청 ${r.vus}개, 측정 ${r.measureSeconds}초 (예열 ${r.warmup} 제외)`,
        `  요청 ${r.requests}건 | 성공 TPS ${r.successTps} | 실패율 ${r.failureRate}%`,
        `  응답(ms) 평균 ${r.latencyMs.avg} | P50 ${r.latencyMs.p50} | P95 ${r.latencyMs.p95} | P99 ${r.latencyMs.p99} | 최대 ${r.latencyMs.max}`,
        `  상태 성공 ${r.statusCounts.ok} | 4xx ${r.statusCounts['4xx']} | 5xx ${r.statusCounts['5xx']} | 응답 없음 ${r.statusCounts.noResponse}`,
        `  1초 초과 ${r.slowOver1s}` + (r.staleResponses !== undefined ? ` | 오래된 환율 응답 ${r.staleResponses}` : '')
            + (r.incompleteResponses !== undefined ? ` | 불완전 응답 ${r.incompleteResponses}` : ''),
        '',
    ].join('\n');
}

export function parseSeconds(value) {
    const match = /^(\d+)(ms|s|m)$/.exec(value);
    if (!match) {
        throw new Error(`DURATION 형식이 올바르지 않습니다: ${value}`);
    }
    const amount = Number(match[1]);
    return { ms: amount / 1000, s: amount, m: amount * 60 }[match[2]];
}

export function round(value, digits = 2) {
    if (value === undefined || Number.isNaN(value)) {
        return null;
    }
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
}
