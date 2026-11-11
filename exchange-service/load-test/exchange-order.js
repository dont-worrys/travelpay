// 환전 신청 부하 측정 스크립트입니다(#282, 원장 서킷 브레이커 개선 전후 비교). measure-ledger.sh가 실행합니다.
//
// 열린 모델(constant-arrival-rate): 응답과 상관없이 초당 정해진 수만큼 요청을 시작합니다.
// 원장이 느려지면 닫힌 모델(동시 N명)은 가상 사용자도 같이 기다려 요청이 저절로 줄어 "요청 스레드가 바닥나는" 상황이 가려집니다.
// 실제 사용자는 서버가 느려도 계속 들어오므로, 이 측정은 요청량을 고정하고 응답 시간·실패·밀린 요청을 봅니다.
//
// 두 부하를 함께 겁니다.
//   orders: 견적 발급 → 환전 신청 (원장을 부름). 매번 새 멱등 키
//   rates : 환율 조회 (원장과 상관없음). 원장 지연이 다른 API까지 끌어내리는지 봄
// 응답 시간 제한은 TIMEOUT(기본 10초). 넘으면 "응답 못 받음"으로 셉니다.
// 요청마다 시작 시각으로 예열/측정을 표시하고(phase), 측정 구간에 시작한 요청만 집계합니다.
//
// 환경 변수
//   ORDER_RATE  초당 환전 신청 수 (기본 40)
//   RATE_RATE   초당 환율 조회 수 (기본 100)
//   WARMUP      예열 (기본 30s) / DURATION 측정 (기본 120s)
//   TIMEOUT     요청 하나의 응답 시간 제한 (기본 10s)
//   LABEL       결과 파일 이름 (results/<LABEL>.json)
//   BASE_URL    환전 서비스 주소 (compose에서 http://app:18084)

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const ORDER_RATE = Number(__ENV.ORDER_RATE || 40);
const RATE_RATE = Number(__ENV.RATE_RATE || 100);
const WARMUP = __ENV.WARMUP || '30s';
const DURATION = __ENV.DURATION || '120s';
const TIMEOUT = __ENV.TIMEOUT || '10s';
const LABEL = __ENV.LABEL || 'order';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18084';
const WARMUP_MS = seconds(WARMUP) * 1000;
const TOTAL = `${seconds(WARMUP) + seconds(DURATION)}s`;

/** wiremock/mappings/wallet-balances.json의 walletId와 같아야 합니다(가짜 지갑이 이 지갑을 돌려줌) */
const WALLET_ID = '33333333-3333-4333-8333-333333333333';
const QUOTE_BODY = JSON.stringify({ fromCurrency: 'KRW', toCurrency: 'JPY', amount: '100000' });

// 측정 구간 지표. 응답 시간은 성공·실패와 상관없이 모두 담는다(사용자가 기다린 시간)
const orderDuration = new Trend('order_duration', true);   // 환전 신청 요청 하나
const quoteDuration = new Trend('quote_duration', true);   // 신청 전 견적 발급
const rateDuration = new Trend('rate_duration', true);     // 환율 조회
const orderStatus = {
    200: new Counter('order_200'), 202: new Counter('order_202'), 422: new Counter('order_422'),
    other: new Counter('order_other'), none: new Counter('order_no_response'),
};
const quoteFailed = new Counter('quote_failed');           // 견적 발급 실패(201 아님). 이때는 신청을 보내지 않음
const quoteNoResponse = new Counter('quote_no_response');
const rateFailed = new Counter('rate_failed');             // 환율 조회 실패(200 아님, 응답 없음 포함)
const rateNoResponse = new Counter('rate_no_response');

export const options = {
    scenarios: {
        orders: {
            executor: 'constant-arrival-rate', exec: 'order',
            rate: ORDER_RATE, timeUnit: '1s', duration: TOTAL,
            // 원장이 느리면 요청 하나가 오래 걸려 동시에 도는 가상 사용자가 늘어난다. 측정 중에 새로 만들면 늦어 k6가 요청을
            // 건너뛰므로(dropped, 스모크 측정에서 228건) 시간 제한(10초) 동안 쌓일 만큼 미리 만든다: 신청 초당 40~60 × 10초
            preAllocatedVUs: 500, maxVUs: 2000,
        },
        rates: {
            executor: 'constant-arrival-rate', exec: 'rate',
            rate: RATE_RATE, timeUnit: '1s', duration: TOTAL,
            preAllocatedVUs: 200, maxVUs: 2000,
        },
    },
    // 기준은 항상 통과하는 값이라 측정을 멈추지 않는다. 시나리오별 dropped_iterations를 요약에 남기려고 둔다
    thresholds: {
        'dropped_iterations{scenario:orders}': ['count>=0'],
        'dropped_iterations{scenario:rates}': ['count>=0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function phase() {
    return exec.instance.currentTestRunDuration < WARMUP_MS ? 'warmup' : 'measure';
}

/** 반복 번호로 회원을 나눈다(회원 100명). 가짜 지갑은 누구에게나 같은 지갑을 돌려준다 */
function userId() {
    return `00000000-0000-4000-8000-${String(exec.scenario.iterationInTest % 100).padStart(12, '0')}`;
}

export function order() {
    const p = phase();
    const headers = { 'Content-Type': 'application/json', 'User-Id': userId(), 'User-Role': 'USER' };
    const quote = http.post(`${BASE_URL}/api/v1/exchange-quotes`, QUOTE_BODY, { headers, timeout: TIMEOUT, tags: { phase: p, api: 'quote' } });
    if (p === 'measure') {
        quoteDuration.add(quote.timings.duration);
    }
    if (quote.status !== 201) {
        if (p === 'measure') {
            quoteFailed.add(1);
            if (quote.status === 0) quoteNoResponse.add(1);
        }
        return;
    }
    const quoteId = quote.json('data.quoteId');
    // 멱등 키는 실행 안에서 겹치지 않게 반복 번호로 만든다
    const key = `load-${exec.scenario.iterationInTest}-${Date.now()}`;
    const response = http.post(`${BASE_URL}/api/v1/exchanges`, JSON.stringify({ quoteId, walletId: WALLET_ID }), {
        headers: { ...headers, 'Idempotency-Key': key }, timeout: TIMEOUT, tags: { phase: p, api: 'order' },
    });
    if (p !== 'measure') {
        return;
    }
    orderDuration.add(response.timings.duration);
    if (response.status === 0) orderStatus.none.add(1);
    else if (orderStatus[response.status]) orderStatus[response.status].add(1);
    else orderStatus.other.add(1);
}

export function rate() {
    const p = phase();
    const response = http.get(`${BASE_URL}/api/v1/exchange-rates`, { timeout: TIMEOUT, tags: { phase: p, api: 'rate' } });
    if (p !== 'measure') {
        return;
    }
    rateDuration.add(response.timings.duration);
    if (response.status !== 200) {
        rateFailed.add(1);
        if (response.status === 0) rateNoResponse.add(1);
    }
}

export function handleSummary(data) {
    const m = (name) => (data.metrics[name] ? data.metrics[name].values : {});
    const trend = (name) => {
        const v = m(name);
        return { avg: round(v.avg), p50: round(v.med), p95: round(v['p(95)']), p99: round(v['p(99)']), max: round(v.max) };
    };
    const c = (name) => m(name).count || 0;
    const measureSeconds = seconds(DURATION);
    const orderResponses = c('order_200') + c('order_202') + c('order_422') + c('order_other') + c('order_no_response');
    const result = {
        label: LABEL,
        orderRate: ORDER_RATE,
        rateRate: RATE_RATE,
        timeout: TIMEOUT,
        warmup: WARMUP,
        measureSeconds,
        // measure-ledger.sh가 측정 구간을 계산하는 데 씀
        testRunDurationMs: Math.round(data.state.testRunDurationMs),
        orders: {
            requests: orderResponses,
            latencyMs: trend('order_duration'),
            status: { 200: c('order_200'), 202: c('order_202'), 422: c('order_422'), other: c('order_other'), noResponse: c('order_no_response') },
            completedTps: round(c('order_200') / measureSeconds),
            quoteLatencyMs: trend('quote_duration'),
            quoteFailed: c('quote_failed'),
            quoteNoResponse: c('quote_no_response'),
            // 서버가 못 따라가 k6가 보내지 못한 신청 수 (예열 포함)
            dropped: c('dropped_iterations{scenario:orders}'),
        },
        rates: {
            latencyMs: trend('rate_duration'),
            failed: c('rate_failed'),
            noResponse: c('rate_no_response'),
            dropped: c('dropped_iterations{scenario:rates}'),
        },
    };
    const text = JSON.stringify(result, null, 2);
    return {
        stdout: `\n== 환전 신청 (${LABEL}) ==\n${text}\n`,
        [`/results/${LABEL}.json`]: text,
    };
}

function seconds(value) {
    const match = /^(\d+)(ms|s|m)?$/.exec(String(value));
    if (!match) throw new Error(`시간 형식을 읽지 못했습니다: ${value}`);
    const n = Number(match[1]);
    return match[2] === 'm' ? n * 60 : match[2] === 'ms' ? n / 1000 : n;
}

function round(value, digits = 2) {
    return value === undefined || value === null ? null : Number(value.toFixed(digits));
}
