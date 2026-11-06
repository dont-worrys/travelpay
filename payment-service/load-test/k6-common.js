// 송금 부하 측정 스크립트(transfer-request.js, transfer-query.js)가 함께 쓰는 설정과 결과 정리입니다.
//
// 측정 방식: 가상 사용자 VUS명이 예열(WARMUP) + 측정(DURATION) 내내 쉬지 않고 요청하고,
// 요청을 "시작한" 시각(k6 테스트 경과 시간)이 예열 이후인 요청만 집계합니다(phase 태그).
// 예열·측정을 시나리오 하나로 두어 예열 때 맺은 연결을 이어 쓰고 동시 요청 수를 VUS개로 유지합니다(환전 측정과 같은 방식).
//
// 공통 환경 변수
//   VUS      동시 요청 수 (기본 20)
//   DURATION 측정 구간 길이 (기본 1m)
//   WARMUP   예열 구간 길이 (기본 15s)
//   LABEL    결과 파일 이름 구분 (예: spread, hot)
//   BASE_URL payment-service 주소 (compose 기본 http://host.docker.internal:8083)

import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

export const VUS = Number(__ENV.VUS || 20);
export const DURATION = __ENV.DURATION || '1m';
export const WARMUP = __ENV.WARMUP || '15s';
export const LABEL = __ENV.LABEL || 'run';
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const WARMUP_MS = parseSeconds(WARMUP) * 1000;

// 측정 구간에서만 올리는 카운터
const okResponses = new Counter('ok_responses');        // 기대한 성공 응답 (송금 요청: 201 COMPLETED, 조회: 200)
const okAll = new Counter('ok_responses_all');          // 예열 포함 성공 응답 (검산용)
const accepted = new Counter('status_202');             // 송금 요청 202 (PROCESSING·MANUAL_REVIEW, 원장 결과 모름)
const rejected = new Counter('status_422');             // 송금 요청 422 (잔액 부족 등 확정 거절)
const tooMany = new Counter('status_429');              // I02 호출 제한
const otherClientErrors = new Counter('status_4xx_other');
const serverErrors = new Counter('status_5xx');
const noResponse = new Counter('no_response');          // 연결 실패·타임아웃 (status 0)
const slowOver1s = new Counter('slow_over_1s');

export const options = {
    scenarios: {
        main: {
            executor: 'constant-vus',
            vus: VUS,
            duration: `${parseSeconds(WARMUP) + parseSeconds(DURATION)}s`,
        },
    },
    // 항상 통과하는 기준이라 측정을 멈추지 않는다. 실패 판단은 measure.py가 결과 JSON으로 한다
    thresholds: {
        'http_req_duration{phase:measure}': ['avg>=0'],
        'http_req_failed{phase:measure}': ['rate>=0'],
        'http_reqs{phase:measure}': ['count>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // 송금 요청은 201·202·422가 모두 서버가 정상 처리한 응답이라 실패율(http_req_failed)에서 뺀다
    // (422는 잔액 부족 같은 업무 거절, 202는 결과 모름 처리). 실패율은 4xx 기타·429·5xx·응답 없음만 센다
};

/** 요청을 시작한 시각으로 예열/측정을 정한다 */
export function currentPhase() {
    return exec.instance.currentTestRunDuration < WARMUP_MS ? 'warmup' : 'measure';
}

/**
 * 응답 하나를 센다. 성공은 예열 포함으로도 세고, 나머지는 측정 구간에서만 센다.
 *
 * @param isOk 스크립트가 정한 성공 판정
 * @returns 측정 구간의 성공 응답이면 true
 */
export function count(response, phase, isOk) {
    if (isOk) {
        okAll.add(1);
    }
    if (phase !== 'measure') {
        return false;
    }
    const status = response.status;
    if (isOk) {
        okResponses.add(1);
    } else if (status === 0) {
        noResponse.add(1);
    } else if (status === 202) {
        accepted.add(1);
    } else if (status === 422) {
        rejected.add(1);
    } else if (status === 429) {
        tooMany.add(1);
    } else if (status >= 500) {
        serverErrors.add(1);
    } else {
        otherClientErrors.add(1);
    }
    if (response.timings.duration > 1000) {
        slowOver1s.add(1);
    }
    return isOk;
}

/** 측정 구간 지표만 골라 화면에 표로 찍고, results/ 폴더에 JSON으로 남긴다 */
export function summarize(data, title, extra = {}) {
    const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
    const duration = metric('http_req_duration{phase:measure}');
    const measureSeconds = parseSeconds(DURATION);
    const ok = metric('ok_responses').count || 0;
    const total = metric('http_reqs{phase:measure}').count || 0;
    const counts = {
        ok,
        '202': metric('status_202').count || 0,
        '422': metric('status_422').count || 0,
        '429': metric('status_429').count || 0,
        '4xxOther': metric('status_4xx_other').count || 0,
        '5xx': metric('status_5xx').count || 0,
        noResponse: metric('no_response').count || 0,
    };
    const failures = counts['429'] + counts['4xxOther'] + counts['5xx'] + counts.noResponse;
    const result = {
        label: LABEL,
        title,
        vus: VUS,
        measureSeconds,
        warmup: WARMUP,
        requests: total,
        requestsAll: metric('http_reqs').count || 0,
        okAll: metric('ok_responses_all').count || 0,
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
        // 오류율: 429·그 밖의 4xx·5xx·응답 없음. 202(결과 모름)·422(업무 거절)는 따로 센다
        errorRate: round(total ? (failures / total) * 100 : 0, 3),
        statusCounts: counts,
        slowOver1s: metric('slow_over_1s').count || 0,
        ...extra,
    };
    return {
        stdout: table(result),
        [`/results/${LABEL}-vus${VUS}.json`]: JSON.stringify(result, null, 2),
    };
}

function table(r) {
    const c = r.statusCounts;
    return [
        '',
        `[${r.label}] ${r.title}, 동시 요청 ${r.vus}개, 측정 ${r.measureSeconds}초 (예열 ${r.warmup} 제외)`,
        `  요청 ${r.requests}건 | 성공 TPS ${r.successTps} | 전체 TPS ${r.totalTps} | 오류율 ${r.errorRate}%`,
        `  응답(ms) 평균 ${r.latencyMs.avg} | P50 ${r.latencyMs.p50} | P95 ${r.latencyMs.p95} | P99 ${r.latencyMs.p99} | 최대 ${r.latencyMs.max}`,
        `  상태 성공 ${c.ok} | 202 ${c['202']} | 422 ${c['422']} | 429 ${c['429']} | 기타 4xx ${c['4xxOther']} | 5xx ${c['5xx']} | 응답 없음 ${c.noResponse}`,
        `  1초 초과 ${r.slowOver1s}`,
        '',
    ].join('\n');
}

export function parseSeconds(value) {
    const match = /^(\d+)(ms|s|m)$/.exec(value);
    if (!match) {
        throw new Error(`시간 형식이 올바르지 않습니다: ${value}`);
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
