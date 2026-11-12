#!/usr/bin/env bash
# 환전 신청 한 회차를 측정합니다(#282, 원장 서킷 브레이커 개선 전후 비교).
# 가짜 지갑·원장(WireMock) 기동 → 원장 응답 지연 설정 → 앱 재시작 → 이력 적재 → k6(exchange-order.js, 열린 모델) 실행
# (그동안 요청 스레드·커넥션 풀 지표를 1초마다 기록) → 주문 상태 집계 → 결과 정리.
# 기존 환율·견적 측정(measure.sh)과 따로 두어 그 측정 조건을 바꾸지 않습니다.
#
# 사용법: [ORDER_RATE=60] [RATE_RATE=100] [TIMEOUT=10s] ./measure-ledger.sh <LABEL> <원장 지연 ms>
#   예: APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger.sh ledger-pre-normal 0
#       APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger.sh ledger-pre-timeout 6000
#   결과: results/<LABEL>.json(k6: 신청·환율 조회 응답) + results/<LABEL>-server.json(서버 지표·주문 상태·측정 조건)
#
# 원장 지연: 0 = 정상, 3000 = 느리지만 성공(앱의 원장 응답 대기 5초 안), 6000 = 시간 초과(결과 모름 → PROCESSING)
# 서버 지표(측정 구간의 1초 표본):
#   요청 스레드: tomcat_threads_busy_threads(사용 중)·tomcat_threads_config_max_threads(최대) — 원장 지연이 스레드를 붙잡는지
#   커넥션 풀 대기·사용 중, PC 전체·앱 CPU
# 주문 상태: 측정 직전 적재 SQL이 주문 테이블을 비우므로, 이번 실행(예열 포함)에서 만든 주문의 상태별 수
set -euo pipefail

LABEL=${1:?LABEL을 주세요 (예: ledger-pre-normal)}
LEDGER_DELAY_MS=${2:?원장 지연 ms를 주세요 (0 / 3000 / 6000)}
WARMUP_SECONDS=${WARMUP_SECONDS:-30}
MEASURE_SECONDS=${MEASURE_SECONDS:-120}
ORDER_RATE=${ORDER_RATE:-60}
RATE_RATE=${RATE_RATE:-100}
TIMEOUT=${TIMEOUT:-10s}
HOURS=${HOURS:-24}
LEDGER_MAPPING_ID=8f1e2a64-2c1b-4d6e-9a1f-0c2b3d4e5f60   # wiremock/mappings/ledger-register.json의 id

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 컨테이너 안 경로(/load-test/...)를 바꾸지 않게
# 앱이 가짜 지갑·원장을 부르고, 요청 스레드 지표를 내보내게 한다 (compose.yaml에서 기본은 둘 다 꺼짐)
export MEASURE_WALLET_URL=http://ledger-stub:8080 MEASURE_LEDGER_URL=http://ledger-stub:8080 MEASURE_TOMCAT_THREADS=true
mkdir -p results
k6_result="results/${LABEL}.json"
samples="results/${LABEL}-server.tsv"
summary="results/${LABEL}-server.json"
# 앞 실행의 결과가 남아 이번 실행 결과로 오인되지 않게 먼저 지운다
rm -f "$summary" "$k6_result"

psql_q() {
    docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -qAt "$@"
}

echo "[1/5] 가짜 지갑·원장 기동, 원장 응답 지연 ${LEDGER_DELAY_MS}ms"
if ! docker compose --profile stub up -d --wait ledger-stub >/dev/null 2>&1; then
    echo "가짜 지갑·원장(ledger-stub)이 뜨지 않았습니다. 마지막 로그:" >&2
    docker compose --profile stub logs --tail 20 ledger-stub >&2
    exit 1
fi
# 매핑 파일의 지연만 바꿔 관리 API로 덮어쓴다. 실제로 바뀌었는지 다시 읽어 확인한다(틀린 지연으로 재면 결과가 무의미)
sed "s/\"fixedDelayMilliseconds\": [0-9]*/\"fixedDelayMilliseconds\": ${LEDGER_DELAY_MS}/" wiremock/mappings/ledger-register.json \
    | curl -fs -X PUT "http://127.0.0.1:18090/__admin/mappings/${LEDGER_MAPPING_ID}" -H 'Content-Type: application/json' --data-binary @- >/dev/null
applied=$(curl -fs "http://127.0.0.1:18090/__admin/mappings/${LEDGER_MAPPING_ID}" | grep -o '"fixedDelayMilliseconds" *: *[0-9]*' | grep -o '[0-9]*$')
if [ "$applied" != "$LEDGER_DELAY_MS" ]; then
    echo "가짜 원장의 지연이 ${LEDGER_DELAY_MS}ms로 바뀌지 않았습니다 (지금 '${applied}')." >&2
    exit 1
fi

echo "[2/5] 환전 서비스 재시작 (준비될 때까지 대기)"
docker compose --profile app up -d --force-recreate --wait app >/dev/null 2>&1
# 신청은 견적(Redis 키)을 읽는다. 측정 Redis가 키를 지우는 정책이면 견적이 사라져 신청이 404로 섞이므로,
# 운영 redis-cache와 같은 noeviction일 때만 잰다 (README "견적 발급 측정"과 같은 설정)
redis_policy=$(docker compose exec -T measure-redis redis-cli CONFIG GET maxmemory-policy | tr -d '\r' | sed -n 2p)
if [ "$redis_policy" != noeviction ]; then
    echo "측정 Redis 정책이 noeviction이 아닙니다 (지금 ${redis_policy})." >&2
    echo "  export MEASURE_REDIS_MAXMEMORY=2gb MEASURE_REDIS_POLICY=noeviction 후 docker compose up -d --force-recreate --wait measure-redis" >&2
    exit 1
fi

echo "[3/5] 환율 이력 적재 (hours=${HOURS}), 캐시 Redis 비우기"
psql_q -v hours="${HOURS}" -f /load-test/seed-rate-history.sql >/dev/null
# 적재 SQL이 환율·주문 표를 비우므로 앱이 캐시에 넣은 환율은 DB에 없는 행이 된다. 캐시를 비워 다음 수집(5초)이 다시 채우게 한다
docker compose exec -T measure-redis redis-cli FLUSHALL >/dev/null
# 비운 캐시를 수집기가 다시 채울 때까지 기다린다(첫 견적이 환율 없음으로 실패하지 않게)
sleep 6

# 측정 조건: PC 상태(측정 전후), 원장 지연, 부하량. 읽지 못하면 측정하지 않는다(measure.sh와 같은 기준)
require_pc_state() {
    if [ -z "$1" ]; then
        echo "PC 상태를 읽지 못했습니다(pc-state.sh 출력 없음). 측정하지 않습니다." >&2
        exit 1
    fi
    if echo "$1" | grep -q '"os": "windows"' && echo "$1" | grep -q '"powerMode": "unknown"\|"charger": "unknown"'; then
        echo "PC 전원 모드나 충전기 상태를 읽지 못했습니다: $1. 측정하지 않습니다." >&2
        exit 1
    fi
}
pc_before=$(bash pc-state.sh)
require_pc_state "$pc_before"
echo "      측정 조건: 원장 지연 ${LEDGER_DELAY_MS}ms, 신청 초당 ${ORDER_RATE} + 환율 조회 초당 ${RATE_RATE}, PC ${pc_before}"

echo "[4/5] k6 실행 (exchange-order.js), 서버 지표 1초 간격 기록"
collect_seconds=$((WARMUP_SECONDS + MEASURE_SECONDS + 20))   # k6 컨테이너가 뜨는 시간 여유
# 요청 스레드는 서비스 포트(18084) 커넥터만 본다. 지표 포트(18085)는 별도 커넥터라 섞지 않는다
docker compose exec -T app sh -c "end=\$((\$(date +%s) + ${collect_seconds})); while [ \$(date +%s) -lt \$end ]; do echo \"ts \$(date +%s)\"; curl -s --max-time 2 http://localhost:18085/actuator/prometheus; sleep 1; done" \
    | awk '
        function flush() { if (ts != "") printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts, busy, max, p, a, sc, pc; busy = max = p = a = sc = pc = "" }
        $1 == "ts" { flush(); ts = $2; next }
        $1 ~ /^tomcat_threads_busy_threads\{.*name="http-nio-18084"/ { busy = $2 }
        $1 ~ /^tomcat_threads_config_max_threads\{.*name="http-nio-18084"/ { max = $2 }
        $1 ~ /^hikaricp_connections_pending\{/ { p = $2 }
        $1 ~ /^hikaricp_connections_active\{/ { a = $2 }
        $1 == "system_cpu_usage" { sc = $2 }
        $1 == "process_cpu_usage" { pc = $2 }
        END { flush() }' >"$samples" &
sampler_pid=$!

k6_started=$(date +%s)
# k6 결과를 쓴 뒤 어디서든 실패하면 반쪽 결과가 남지 않게 지운다(measure.sh와 같음). 다 만든 뒤에 푼다
trap 'rm -f "$k6_result" "$summary.tmp"' EXIT
docker compose run --rm -e LABEL="${LABEL}" -e WARMUP="${WARMUP_SECONDS}s" -e DURATION="${MEASURE_SECONDS}s" \
    -e ORDER_RATE="${ORDER_RATE}" -e RATE_RATE="${RATE_RATE}" -e TIMEOUT="${TIMEOUT}" k6 run --quiet /scripts/exchange-order.js
k6_ended=$(date +%s)
if ! pc_after=$(bash pc-state.sh) || ! (require_pc_state "$pc_after"); then
    exit 1
fi
standby=$(bash standby-events.sh "$k6_started" "$k6_ended")
if [ "$standby" != null ] && ! [[ "$standby" =~ ^[0-9]+$ ]]; then
    echo "무효 측정: k6 구간의 대기 모드 진입 여부를 확인하지 못했습니다(standby-events.sh 결과 '${standby}')." >&2
    exit 1
fi
if [ "$standby" != null ] && [ "$standby" != 0 ]; then
    echo "무효 측정: k6가 도는 동안 PC가 대기 모드에 ${standby}번 들어갔습니다." >&2
    exit 1
fi
pc_power() { echo "$1" | grep -o '"powerMode": [^,]*\|"charger": [^,]*' | tr '\n' ' '; }
if [ "$(pc_power "$pc_before")" != "$(pc_power "$pc_after")" ]; then
    echo "무효 측정: 측정 중에 전원 모드나 충전기 연결이 바뀌었습니다. 직전 ${pc_before} / 직후 ${pc_after}" >&2
    exit 1
fi

# 측정 구간: k6가 실제로 테스트를 시작한 시각 + 예열 ~ 그로부터 측정 시간
run_ms=$(grep -o '"testRunDurationMs": [0-9]*' "$k6_result" | grep -o '[0-9]*$')
if [ -z "$run_ms" ]; then
    echo "k6 결과에서 testRunDurationMs를 찾지 못했습니다: $k6_result" >&2
    exit 1
fi
measure_from=$((k6_ended - (run_ms + 999) / 1000 + WARMUP_SECONDS))
measure_to=$((measure_from + MEASURE_SECONDS))
wait "$sampler_pid" 2>/dev/null || true

echo "[5/5] 서버 지표·주문 상태 정리"
# 이번 실행에서 만든 주문의 상태별 수(예열 포함)와, 측정 구간에 신청한 주문의 상태별 수
orders_all=$(psql_q -F ' ' -c "SELECT status, count(*) FROM exchange_service.exchange_orders GROUP BY status ORDER BY status" | awk '{ printf "%s\"%s\": %d", (n++ ? ", " : ""), $1, $2 }')
orders_measure=$(psql_q -F ' ' -c "SELECT status, count(*) FROM exchange_service.exchange_orders WHERE requested_at >= to_timestamp(${measure_from}) AND requested_at < to_timestamp(${measure_to}) GROUP BY status ORDER BY status" | awk '{ printf "%s\"%s\": %d", (n++ ? ", " : ""), $1, $2 }')

awk -F'\t' -v from="$measure_from" -v to="$measure_to" -v label="$LABEL" -v delay="$LEDGER_DELAY_MS" \
    -v orderRate="$ORDER_RATE" -v rateRate="$RATE_RATE" -v timeout="$TIMEOUT" \
    -v pcBefore="$pc_before" -v pcAfter="$pc_after" -v standby="$standby" \
    -v ordersAll="$orders_all" -v ordersMeasure="$orders_measure" '
    $1 < from || $1 > to { next }
    # 지표가 하나라도 빠진 표본은 버린다(빈 값이 0으로 계산돼 평균이 틀어지지 않게). 버린 만큼 틈이 생겨 아래 틈 검사에 걸림
    $2 == "" || $3 == "" || $4 == "" || $5 == "" || $6 == "" || $7 == "" { next }
    {
        if (n == 0) t0 = $1
        else if ($1 - t1 > maxGap) maxGap = $1 - t1
        t1 = $1
        n++; busy += $2; pend += $4; act += $5; sys += $6; proc += $7
        if ($2 > busyMax) busyMax = $2
        if ($4 > pendMax) pendMax = $4
        maxThreads = $3
        if ($2 >= $3) saturated++   # 요청 스레드가 모두 사용 중인 초
    }
    END {
        if (n == 0) { print "서버 지표 표본이 없습니다: 요청 스레드 지표(tomcat_threads_*)를 읽지 못함. MEASURE_TOMCAT_THREADS가 켜졌는지 확인합니다" > "/dev/stderr"; exit 1 }
        gap = 3
        if (t0 > from + gap || t1 < to - gap || maxGap > gap) {
            printf "표본이 측정 구간(%d~%d)을 다 덮지 못했습니다: %d~%d, 가장 긴 틈 %d초 (허용 %d초)\n", from, to, t0, t1, maxGap, gap > "/dev/stderr"
            exit 1
        }
        printf "{\n"
        printf "  \"label\": \"%s\",\n", label
        printf "  \"conditions\": {\"ledgerDelayMs\": %d, \"orderRate\": %d, \"rateRate\": %d, \"timeout\": \"%s\", \"pcBefore\": %s, \"pcAfter\": %s, \"standbyEntries\": %s},\n", delay, orderRate, rateRate, timeout, pcBefore, pcAfter, standby
        printf "  \"samples\": %d,\n", n
        printf "  \"tomcatThreads\": {\"busyAvg\": %.1f, \"busyMax\": %d, \"max\": %d, \"saturatedSeconds\": %d},\n", busy / n, busyMax, maxThreads, saturated
        printf "  \"hikariPending\": {\"avg\": %.2f, \"max\": %d},\n", pend / n, pendMax
        printf "  \"hikariActiveAvg\": %.2f,\n", act / n
        printf "  \"cpuPercent\": {\"systemAvg\": %.1f, \"appAvg\": %.1f},\n", sys / n * 100, proc / n * 100
        printf "  \"orders\": {\"all\": {%s}, \"measure\": {%s}}\n", ordersAll, ordersMeasure
        printf "}\n"
    }' "$samples" >"$summary.tmp" || {
        rm -f "$summary.tmp" "$k6_result"
        exit 1
    }
mv "$summary.tmp" "$summary"
trap - EXIT

cat "$summary"
