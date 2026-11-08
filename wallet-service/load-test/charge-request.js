// 충전 접수(POST /api/v1/charges) 부하 측정 스크립트입니다. 실행 방법은 README.md를 봅니다.
//
// 측정 방식: 가상 사용자 닫힌 루프가 아니라 constant-arrival-rate로 초당 RATE건을 COUNT건까지 시작합니다.
// 응답이 늦어도 요청 시작 간격은 그대로이고, VU가 모자라 시작하지 못한 건은 dropped_iterations에 남습니다.
// 요청마다 새 Idempotency-Key(UUID), 측정 회원을 돌아가며 씁니다(data/users.json, prepare.py가 만듦).
// 접수 성공은 202(처리 중)입니다. 실제 접수 건수·완료 처리량·정합성은 measure.py가 DB로 계산합니다.
//
// 환경 변수
//   COUNT       시작할 충전 건수 (기본 100)
//   RATE        초당 시작 건수 (기본 10)
//   PRE_VUS     미리 만들 VU 수 (기본 max(20, RATE))
//   MAX_VUS     최대 VU 수 (기본 max(100, RATE × 4))
//   AMOUNT      충전 금액(원, 기본 10000)
//   LABEL       결과 파일 이름 구분
//   CONCURRENCY 지갑 SAGA 컨슈머 동시성 (메타데이터. 스크립트가 바꾸지 않음)
//   BASE_URL    wallet-service 주소 (compose 기본 http://host.docker.internal:8082)

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const COUNT = Number(__ENV.COUNT || 100);
const RATE = Number(__ENV.RATE || 10);
const PRE_VUS = Number(__ENV.PRE_VUS || Math.max(20, RATE));
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(100, RATE * 4));
const AMOUNT = Number(__ENV.AMOUNT || 10000);
const LABEL = __ENV.LABEL || 'run';
const CONCURRENCY = __ENV.CONCURRENCY || 'unknown';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082';
const DURATION_SECONDS = Math.ceil(COUNT / RATE);
const DATA = JSON.parse(open('./data/users.json'));

const status202 = new Counter('status_202');
const status200 = new Counter('status_200');           // 같은 키 재요청(이미 끝난 충전). 새 키만 쓰므로 0이어야 함
const clientErrors = new Counter('status_4xx');
const serverErrors = new Counter('status_5xx');
const noResponse = new Counter('no_response');          // 연결 실패·타임아웃 (status 0)
const slowOver1s = new Counter('slow_over_1s');
const skipped = new Counter('skipped_over_count');      // RATE × 시간이 COUNT를 넘는 마지막 몇 건

export const options = {
    scenarios: {
        charge: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: `${DURATION_SECONDS}s`,
            preAllocatedVUs: PRE_VUS,
            maxVUs: MAX_VUS,
        },
    },
    // 항상 통과하는 기준이라 측정을 멈추지 않는다. 실패 판단은 measure.py가 결과 JSON으로 한다
    thresholds: {
        http_req_duration: ['avg>=0'],
        http_reqs: ['count>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(202));

export function setup() {
    if (!DATA.users || DATA.users.length === 0) {
        throw new Error('data/users.json에 회원이 없습니다. prepare.py를 먼저 실행하세요.');
    }
    if (!(COUNT > 0) || !(RATE > 0)) {
        throw new Error(`COUNT·RATE는 1 이상이어야 합니다: COUNT=${COUNT} RATE=${RATE}`);
    }
}

export default function () {
    const iteration = exec.scenario.iterationInTest;
    if (iteration >= COUNT) {
        skipped.add(1);
        return;
    }
    const user = DATA.users[iteration % DATA.users.length];
    const response = http.post(`${BASE_URL}/api/v1/charges`,
        JSON.stringify({ amount: AMOUNT, currency: 'KRW', bankCode: DATA.bankCode, accountNumber: user.accountNumber }), {
            headers: {
                'Content-Type': 'application/json',
                'User-Id': user.userId,
                'User-Role': 'USER',
                // 표준 UUID 표기만 받는다 (IdempotencyKeys.parse)
                'Idempotency-Key': crypto.randomUUID(),
            },
            tags: { name: 'POST /api/v1/charges' },
        });
    const status = response.status;
    if (status === 202) {
        status202.add(1);
    } else if (status === 200) {
        status200.add(1);
    } else if (status === 0) {
        noResponse.add(1);
    } else if (status >= 500) {
        serverErrors.add(1);
    } else {
        clientErrors.add(1);
    }
    if (response.timings.duration > 1000) {
        slowOver1s.add(1);
    }
}

export function handleSummary(data) {
    const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
    const duration = metric('http_req_duration');
    const counts = {
        '202': metric('status_202').count || 0,
        '200': metric('status_200').count || 0,
        '4xx': metric('status_4xx').count || 0,
        '5xx': metric('status_5xx').count || 0,
        noResponse: metric('no_response').count || 0,
    };
    const result = {
        label: LABEL,
        concurrency: CONCURRENCY,
        count: COUNT,
        rate: RATE,
        durationSeconds: DURATION_SECONDS,
        preAllocatedVUs: PRE_VUS,
        maxVUs: MAX_VUS,
        amount: AMOUNT,
        requests: metric('http_reqs').count || 0,
        iterations: metric('iterations').count || 0,
        skippedOverCount: metric('skipped_over_count').count || 0,
        droppedIterations: metric('dropped_iterations').count || 0,
        vusMax: metric('vus_max').max || metric('vus_max').value || null,
        testRunDurationMs: Math.round(data.state.testRunDurationMs),
        latencyMs: {
            avg: round(duration.avg),
            p50: round(duration.med),
            p95: round(duration['p(95)']),
            p99: round(duration['p(99)']),
            max: round(duration.max),
        },
        statusCounts: counts,
        // 202 외 응답 수 (200 재요청 포함)
        errorResponses: counts['200'] + counts['4xx'] + counts['5xx'] + counts.noResponse,
        slowOver1s: metric('slow_over_1s').count || 0,
    };
    return {
        stdout: table(result),
        [`/results/${LABEL}-c${CONCURRENCY}.json`]: JSON.stringify(result, null, 2),
    };
}

function table(r) {
    const c = r.statusCounts;
    return [
        '',
        `[${r.label}] 충전 접수, 동시성 ${r.concurrency}, 초당 ${r.rate}건 × ${r.durationSeconds}초 (목표 ${r.count}건)`,
        `  요청 ${r.requests}건 | dropped ${r.droppedIterations} | 202 외 응답 ${r.errorResponses}`,
        `  접수 응답(ms) 평균 ${r.latencyMs.avg} | P50 ${r.latencyMs.p50} | P95 ${r.latencyMs.p95} | P99 ${r.latencyMs.p99} | 최대 ${r.latencyMs.max}`,
        `  상태 202 ${c['202']} | 200 ${c['200']} | 4xx ${c['4xx']} | 5xx ${c['5xx']} | 응답 없음 ${c.noResponse} | 1초 초과 ${r.slowOver1s}`,
        '',
    ].join('\n');
}

function round(value, digits = 2) {
    if (value === undefined || Number.isNaN(value)) {
        return null;
    }
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
}
