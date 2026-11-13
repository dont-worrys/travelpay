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
//
// 급증(spike) 측정: LOAD=spike이면 가상 사용자 수를 고정하지 않고 초당 요청 수를 정해 보냅니다(ramping-arrival-rate).
// 서버가 느려져도 요청이 정한 속도로 계속 들어오므로, 짧은 급증 때 대기가 쌓였다가 풀리는 모습을 봅니다.
// 예열(WARMUP, BASE_RATE) → before(SPIKE_BEFORE, BASE_RATE) → 상승(SPIKE_RISE) → 유지(SPIKE_HOLD, PEAK_RATE)
// → 하강(SPIKE_FALL) → after(SPIKE_AFTER, BASE_RATE). 상승·유지·하강을 spike 구간으로 묶고 stage 태그로 따로 집계합니다.
// 이때 DURATION은 쓰지 않고 측정 구간 = before + spike + after입니다. VUS는 동시 요청 상한(maxVUs, 미리 모두 만듦)이고,
// 상한에 막혀 보내지 못한 요청은 droppedIterations로 셉니다(0이 아니면 클라이언트가 목표 속도를 못 낸 측정).
//   LOAD          constant(기본) | spike
//   BASE_RATE     평상시 초당 요청 수 (기본 20)
//   PEAK_RATE     급증 때 초당 요청 수 (기본 150)
//   SPIKE_BEFORE  급증 전 평상시 구간 (기본 30s)
//   SPIKE_RISE    평상시 → 급증 상승 시간 (기본 5s)
//   SPIKE_HOLD    급증 유지 (기본 30s)
//   SPIKE_FALL    급증 → 평상시 하강 시간 (기본 5s)
//   SPIKE_AFTER   급증 뒤 평상시 구간. 쌓인 대기가 풀리는지 봄 (기본 60s)

import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

export const VUS = Number(__ENV.VUS || 20);
export const DURATION = __ENV.DURATION || '1m';
export const WARMUP = __ENV.WARMUP || '15s';
export const LABEL = __ENV.LABEL || 'run';
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const WARMUP_MS = parseSeconds(WARMUP) * 1000;
export const LOAD = __ENV.LOAD || 'constant';
export const SPIKE = LOAD === 'spike';
const BASE_RATE = Number(__ENV.BASE_RATE || 20);
const PEAK_RATE = Number(__ENV.PEAK_RATE || 150);
const SPIKE_BEFORE = parseSeconds(__ENV.SPIKE_BEFORE || '30s');
const SPIKE_RISE = parseSeconds(__ENV.SPIKE_RISE || '5s');
const SPIKE_HOLD = parseSeconds(__ENV.SPIKE_HOLD || '30s');
const SPIKE_FALL = parseSeconds(__ENV.SPIKE_FALL || '5s');
const SPIKE_AFTER = parseSeconds(__ENV.SPIKE_AFTER || '60s');
/** 급증 측정의 집계 구간 (측정 시작부터 차례로) */
export const SPIKE_STAGES = [
    { name: 'before', seconds: SPIKE_BEFORE },
    { name: 'spike', seconds: SPIKE_RISE + SPIKE_HOLD + SPIKE_FALL },
    { name: 'after', seconds: SPIKE_AFTER },
];
/** 집계하는 측정 구간 길이(초). 급증 측정은 before + spike + after */
export const MEASURE_SECONDS = SPIKE
    ? SPIKE_STAGES.reduce((sum, stage) => sum + stage.seconds, 0)
    : parseSeconds(DURATION);

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
const failureResponses = new Counter('failure_responses'); // 429·기타 4xx·5xx·응답 없음 (급증 측정 stage별 오류율)

const constantScenario = {
    executor: 'constant-vus',
    vus: VUS,
    duration: `${parseSeconds(WARMUP) + parseSeconds(DURATION)}s`,
};

const spikeScenario = {
    executor: 'ramping-arrival-rate',
    startRate: BASE_RATE,
    timeUnit: '1s',
    preAllocatedVUs: VUS,
    maxVUs: VUS,
    stages: [
        { target: BASE_RATE, duration: `${parseSeconds(WARMUP) + SPIKE_BEFORE}s` },
        { target: PEAK_RATE, duration: `${SPIKE_RISE}s` },
        { target: PEAK_RATE, duration: `${SPIKE_HOLD}s` },
        { target: BASE_RATE, duration: `${SPIKE_FALL}s` },
        { target: BASE_RATE, duration: `${SPIKE_AFTER}s` },
    ],
};

// stage별 지표는 기준(항상 통과)으로 등록해야 handleSummary에 나온다
const stageThresholds = SPIKE ? Object.fromEntries(SPIKE_STAGES.flatMap(({ name }) => [
    [`http_req_duration{phase:measure,stage:${name}}`, ['avg>=0']],
    [`http_reqs{phase:measure,stage:${name}}`, ['count>=0']],
    [`ok_responses{stage:${name}}`, ['count>=0']],
    [`status_202{stage:${name}}`, ['count>=0']],
    [`failure_responses{stage:${name}}`, ['count>=0']],
])) : {};

export const options = {
    scenarios: {
        main: SPIKE ? spikeScenario : constantScenario,
    },
    // 항상 통과하는 기준이라 측정을 멈추지 않는다. 실패 판단은 measure.py가 결과 JSON으로 한다
    thresholds: {
        'http_req_duration{phase:measure}': ['avg>=0'],
        'http_req_failed{phase:measure}': ['rate>=0'],
        'http_reqs{phase:measure}': ['count>=0'],
        ...stageThresholds,
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // k6는 기본으로 반복마다 쿠키를 지운다. Gateway 경유 측정은 처음 로그인한 세션 쿠키를 계속 써야 하므로 유지한다
    // (직접 호출 측정은 쿠키를 받지 않아 영향 없음)
    noCookiesReset: true,
    // 송금 요청은 201·202·422가 모두 서버가 정상 처리한 응답이라 실패율(http_req_failed)에서 뺀다
    // (422는 잔액 부족 같은 업무 거절, 202는 결과 모름 처리). 실패율은 4xx 기타·429·5xx·응답 없음만 센다
};

/** 요청을 시작한 시각으로 예열/측정을 정한다 */
export function currentPhase() {
    return exec.instance.currentTestRunDuration < WARMUP_MS ? 'warmup' : 'measure';
}

/** 급증 측정에서 요청을 시작한 시각의 stage(before|spike|after). 급증 측정이 아니거나 예열 중이면 null */
export function currentStage() {
    const elapsedSeconds = (exec.instance.currentTestRunDuration - WARMUP_MS) / 1000;
    if (!SPIKE || elapsedSeconds < 0) {
        return null;
    }
    let end = 0;
    for (const stage of SPIKE_STAGES) {
        end += stage.seconds;
        if (elapsedSeconds < end) {
            return stage.name;
        }
    }
    return SPIKE_STAGES[SPIKE_STAGES.length - 1].name;   // 마지막 순간에 시작한 요청
}

/**
 * 응답 하나를 센다. 성공은 예열 포함으로도 세고, 나머지는 측정 구간에서만 센다.
 *
 * @param isOk 스크립트가 정한 성공 판정
 * @param stage 급증 측정의 stage(currentStage). 있으면 성공·202·오류 카운터에 stage 태그를 단다
 * @returns 측정 구간의 성공 응답이면 true
 */
export function count(response, phase, isOk, stage = null) {
    if (isOk) {
        okAll.add(1);
    }
    if (phase !== 'measure') {
        return false;
    }
    const status = response.status;
    const tags = stage ? { stage } : {};
    if (isOk) {
        okResponses.add(1, tags);
    } else if (status === 202) {
        accepted.add(1, tags);
    } else if (status === 422) {
        rejected.add(1);
    } else {
        failureResponses.add(1, tags);
        if (status === 0) {
            noResponse.add(1);
        } else if (status === 429) {
            tooMany.add(1);
        } else if (status >= 500) {
            serverErrors.add(1);
        } else {
            otherClientErrors.add(1);
        }
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
    const measureSeconds = MEASURE_SECONDS;
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
    if (SPIKE) {
        result.load = {
            type: 'spike', baseRate: BASE_RATE, peakRate: PEAK_RATE, maxVus: VUS,
            riseSeconds: SPIKE_RISE, holdSeconds: SPIKE_HOLD, fallSeconds: SPIKE_FALL,
        };
        // 동시 요청 상한(VUS)에 막혀 목표 속도로 보내지 못한 요청. 0이 아니면 클라이언트 한계가 섞인 측정
        result.droppedIterations = metric('dropped_iterations').count || 0;
        result.stages = stageSummary(metric);
    }
    return {
        stdout: table(result) + (SPIKE ? stageTable(result) : ''),
        [`/results/${LABEL}-vus${VUS}.json`]: JSON.stringify(result, null, 2),
    };
}

/** 급증 측정의 stage별 요청 수·성공 TPS·202 비율·오류율·지연 */
function stageSummary(metric) {
    return Object.fromEntries(SPIKE_STAGES.map(({ name, seconds }) => {
        const duration = metric(`http_req_duration{phase:measure,stage:${name}}`);
        const requests = metric(`http_reqs{phase:measure,stage:${name}}`).count || 0;
        const ok = metric(`ok_responses{stage:${name}}`).count || 0;
        const status202 = metric(`status_202{stage:${name}}`).count || 0;
        const failures = metric(`failure_responses{stage:${name}}`).count || 0;
        return [name, {
            seconds,
            requests,
            ok,
            successTps: round(ok / seconds),
            status202,
            rate202: round(requests ? (status202 / requests) * 100 : 0, 3),
            errorRate: round(requests ? (failures / requests) * 100 : 0, 3),
            latencyMs: { avg: round(duration.avg), p50: round(duration.med), p95: round(duration['p(95)']),
                p99: round(duration['p(99)']), max: round(duration.max) },
        }];
    }));
}

function stageTable(r) {
    const l = r.load;
    const lines = [`  급증 평상시 ${l.baseRate}/s → 최대 ${l.peakRate}/s (상승 ${l.riseSeconds}s, 유지 ${l.holdSeconds}s,`
        + ` 하강 ${l.fallSeconds}s), 동시 요청 상한 ${l.maxVus}, 못 보낸 요청 ${r.droppedIterations}`];
    for (const [name, s] of Object.entries(r.stages)) {
        lines.push(`  [${name}] ${s.seconds}s 요청 ${s.requests}건 | 성공 TPS ${s.successTps} | 202 ${s.rate202}%`
            + ` | 오류율 ${s.errorRate}% | P95 ${s.latencyMs.p95} | P99 ${s.latencyMs.p99} | 최대 ${s.latencyMs.max}`);
    }
    return `${lines.join('\n')}\n\n`;
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
