// 예약 체결이 환율 수집을 늦추는지 보는 견적 발급 스크립트입니다(#282). measure-ledger.sh가 RESERVATIONS와 함께 실행합니다.
//
// 견적 발급만 초당 QUOTE_RATE건 꾸준히 보냅니다(열린 모델). 견적은 원장을 부르지 않지만 최신 환율이 있어야 합니다.
// mock 환율은 5초마다 수집되고, 수집한 지 30초가 넘으면 견적이 503 EXCHANGE_RATE_UNAVAILABLE입니다.
// 예약 체결은 수집 스레드에서 원장을 부르므로, 원장이 느리면 체결하는 동안 다음 수집이 늦어져 견적이 막힐 수 있습니다.
// 그 503이 몇 건, 언제부터 나는지를 셉니다.
//
// 환경 변수: QUOTE_RATE(기본 10), WARMUP(기본 0s), DURATION(기본 120s), TIMEOUT(기본 10s), LABEL, BASE_URL

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const QUOTE_RATE = Number(__ENV.QUOTE_RATE || 10);
const WARMUP = __ENV.WARMUP || '0s';
const DURATION = __ENV.DURATION || '120s';
const TIMEOUT = __ENV.TIMEOUT || '10s';
const LABEL = __ENV.LABEL || 'quote-freshness';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:18084';
const WARMUP_MS = seconds(WARMUP) * 1000;
const BODY = JSON.stringify({ fromCurrency: 'KRW', toCurrency: 'JPY', amount: '100000' });

const latency = new Trend('quote_duration', true);
const ok = new Counter('quote_201');
const rateUnavailable = new Counter('quote_rate_unavailable');   // 503 EXCHANGE_RATE_UNAVAILABLE: 환율이 오래됨
const otherFailed = new Counter('quote_other_failed');
const noResponse = new Counter('quote_no_response');
// 환율이 오래돼 막힌 첫·마지막 시각(유닉스 초). DB의 환율 수집 시각과 바로 맞춰 보려고 실제 시각으로 남긴다
const firstUnavailableAt = new Trend('quote_first_unavailable_epoch');
const lastUnavailableAt = new Trend('quote_last_unavailable_epoch');

export const options = {
    scenarios: {
        quotes: {
            executor: 'constant-arrival-rate', exec: 'quote',
            rate: QUOTE_RATE, timeUnit: '1s', duration: `${seconds(WARMUP) + seconds(DURATION)}s`,
            preAllocatedVUs: 50, maxVUs: 500,
        },
    },
    thresholds: { 'dropped_iterations{scenario:quotes}': ['count>=0'] },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'max'],
};

export function quote() {
    const elapsedMs = exec.instance.currentTestRunDuration;
    if (elapsedMs < WARMUP_MS) {
        http.post(`${BASE_URL}/api/v1/exchange-quotes`, BODY, { headers: headers(), timeout: TIMEOUT });
        return;
    }
    const response = http.post(`${BASE_URL}/api/v1/exchange-quotes`, BODY, { headers: headers(), timeout: TIMEOUT });
    latency.add(response.timings.duration);
    if (response.status === 201) {
        ok.add(1);
    } else if (response.status === 0) {
        noResponse.add(1);
    } else if (response.status === 503 && String(response.body).includes('EXCHANGE_RATE_UNAVAILABLE')) {
        rateUnavailable.add(1);
        const at = Date.now() / 1000;
        firstUnavailableAt.add(at);
        lastUnavailableAt.add(at);
    } else {
        otherFailed.add(1);
    }
}

function headers() {
    const user = `00000000-0000-4000-8000-${String(exec.scenario.iterationInTest % 100).padStart(12, '0')}`;
    return { 'Content-Type': 'application/json', 'User-Id': user, 'User-Role': 'USER' };
}

export function handleSummary(data) {
    const m = (name) => (data.metrics[name] ? data.metrics[name].values : {});
    const c = (name) => m(name).count || 0;
    const d = m('quote_duration');
    const result = {
        label: LABEL,
        quoteRate: QUOTE_RATE,
        warmup: WARMUP,
        measureSeconds: seconds(DURATION),
        testRunDurationMs: Math.round(data.state.testRunDurationMs),
        quotes: {
            ok: c('quote_201'),
            rateUnavailable: c('quote_rate_unavailable'),
            otherFailed: c('quote_other_failed'),
            noResponse: c('quote_no_response'),
            latencyMs: { p50: round(d.med), p95: round(d['p(95)']), max: round(d.max) },
            // 환율이 오래돼 막힌 첫·마지막 시각(유닉스 초). 없으면 null
            rateUnavailableFromEpoch: c('quote_rate_unavailable') ? round(m('quote_first_unavailable_epoch').min, 1) : null,
            rateUnavailableToEpoch: c('quote_rate_unavailable') ? round(m('quote_last_unavailable_epoch').max, 1) : null,
            dropped: c('dropped_iterations{scenario:quotes}'),
        },
    };
    const text = JSON.stringify(result, null, 2);
    return { stdout: `\n== 견적 발급·환율 신선도 (${LABEL}) ==\n${text}\n`, [`/results/${LABEL}.json`]: text };
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
