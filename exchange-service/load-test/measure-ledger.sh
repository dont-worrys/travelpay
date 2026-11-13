#!/usr/bin/env bash
# 환전 신청 한 회차를 측정합니다(#282, 원장 서킷 브레이커 개선 전후 비교).
# 가짜 지갑·원장(WireMock) 기동 → 원장 응답 지연 설정 → 앱 재시작 → 이력 적재 → k6(exchange-order.js, 열린 모델) 실행
# (그동안 요청 스레드·커넥션 풀 지표를 1초마다 기록) → 주문 상태 집계 → 결과 정리.
# 기존 환율·견적 측정(measure.sh)과 따로 두어 그 측정 조건을 바꾸지 않습니다.
#
# 사용법: [ORDER_RATE=60] [RATE_RATE=100] [TIMEOUT=10s] [K6_PROMETHEUS_RW=1] ./measure-ledger.sh <LABEL> <원장 지연 ms>
#   예: APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger.sh ledger-pre-normal 0
#       APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger.sh ledger-pre-timeout 6000
#   결과: results/<LABEL>.json(k6: 신청·환율 조회 응답) + results/<LABEL>-server.json(서버 지표·주문 상태·측정 조건)
#
# 예약 체결 영향(RESERVATIONS>0): k6 직전에 바로 체결될 대기 예약을 N건 넣고(원화 → 엔화, 목표 환율 99999),
# 견적 발급만 꾸준히 보내(K6_SCRIPT=quote-freshness.js) 예약 체결이 환율 수집을 늦추는지 본다. 체결은 수집 스레드에서 원장을 부르므로
# 원장이 느리면 그만큼 다음 수집이 늦어지고, mock 환율이 30초 넘게 오래되면 견적이 503(EXCHANGE_RATE_UNAVAILABLE)이 된다.
#   예: RESERVATIONS=60 K6_SCRIPT=quote-freshness.js WARMUP_SECONDS=0 ./measure-ledger.sh resv-pre-timeout 6000
#   결과 서버 지표에 환율 수집 간격(가장 긴 틈)·예약 상태별 수가 붙는다
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
RESERVATIONS=${RESERVATIONS:-0}            # 0이면 예약을 넣지 않음 (신청 부하 측정)
K6_SCRIPT=${K6_SCRIPT:-exchange-order.js}   # 예약 체결 영향은 quote-freshness.js
QUOTE_RATE=${QUOTE_RATE:-10}               # quote-freshness.js의 초당 견적 발급 수
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
# 회차마다 새로 만든다. 측정 환경을 내렸다 올리면(docker compose down) 멈춰 둔 컨테이너가 지워진 옛 네트워크를 가리켜 다시 시작하지 못함
if ! docker compose --profile stub up -d --force-recreate --wait ledger-stub >/dev/null 2>&1; then
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

echo "[4/5] k6 실행 (${K6_SCRIPT}), 서버 지표 1초 간격 기록"
# 수집기는 k6가 끝나면 앱 컨테이너 안의 표시 파일(/tmp/stop-sampler)을 보고 멈춘다(Windows에서는 백그라운드 docker를 kill로 못 멈춤).
# 그 신호가 오지 않을 때를 대비한 최대 시간은 넉넉히 둔다. k6 컨테이너가 가끔 1분 넘게 늦게 시작해(2026-11-12 스모크),
# 짧게 두면 수집기가 측정 구간 전에 끝나 표본이 비었다
collect_seconds=$((WARMUP_SECONDS + MEASURE_SECONDS + 300))
# 요청 스레드는 서비스 포트(18084) 커넥터만 본다. 지표 포트(18085)는 별도 커넥터라 섞지 않는다
docker compose exec -T app sh -c "rm -f /tmp/stop-sampler; end=\$((\$(date +%s) + ${collect_seconds})); while [ \$(date +%s) -lt \$end ] && [ ! -f /tmp/stop-sampler ]; do echo \"ts \$(date +%s)\"; curl -s --max-time 2 http://localhost:18085/actuator/prometheus; sleep 1; done" \
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
# 캡처용(K6_PROMETHEUS_RW=1): k6가 잰 사용자 쪽 응답 시간을 Prometheus로도 보낸다(remote write, 네이티브 히스토그램).
# 서버 지표(http_server_requests)는 스레드를 기다린 시간을 재지 않아, 요청 스레드가 꽉 차면 환율 조회·견적이 빠르게 보인다.
# Prometheus가 측정 네트워크에 붙어 있고 remote write 수신을 켠 경우에만 쓴다(README "원장 장애 측정"). 기본은 꺼짐
k6_output=()
if [ "${K6_PROMETHEUS_RW:-0}" = 1 ]; then
    k6_output=(-e K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write
        -e K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true -e K6_PROMETHEUS_RW_PUSH_INTERVAL=5s)
fi
docker compose run --rm -e LABEL="${LABEL}" -e WARMUP="${WARMUP_SECONDS}s" -e DURATION="${MEASURE_SECONDS}s" \
    -e ORDER_RATE="${ORDER_RATE}" -e RATE_RATE="${RATE_RATE}" -e QUOTE_RATE="${QUOTE_RATE}" -e TIMEOUT="${TIMEOUT}" \
    "${k6_output[@]}" k6 run --quiet ${k6_output:+-o experimental-prometheus-rw} "/scripts/${K6_SCRIPT}" &
k6_pid=$!

# 예약 체결 영향: k6가 실제로 요청을 보내기 시작한 뒤에 바로 체결될 대기 예약을 넣는다. 다음 수집(최대 5초 뒤)이 모두 매칭해
# 수집 스레드에서 차례로 체결한다. k6 명령 직전에 넣으면 k6가 늦게 시작할 때 체결이 측정 구간 전에 일어나 결과가 틀어진다.
# 시작은 앱이 받은 견적 요청 수(Actuator)로 확인한다. 회원마다 대기 예약 10건 한도가 있어(API 검사) 예약마다 다른 회원으로 넣고,
# 지갑은 가짜 지갑이 돌려주는 것과 같게 한다
reservations_inserted_at=null
if [ "$RESERVATIONS" -gt 0 ]; then
    # 앱은 Actuator 중 health·prometheus만 열어 둬서(/actuator/metrics는 404) prometheus 응답에 견적 요청 카운터가 생겼는지 본다
    started=false
    for _ in $(seq 1 280); do
        if docker compose exec -T app sh -c 'curl -s http://localhost:18085/actuator/prometheus | grep -q "http_server_requests_seconds_count{.*uri=\"/api/v1/exchange-quotes\""'; then
            started=true
            break
        fi
        sleep 1
    done
    if [ "$started" != true ]; then
        echo "k6가 7분 넘게 견적 요청을 보내지 않아 예약을 넣지 못했습니다. 측정하지 않습니다." >&2
        wait "$k6_pid" || true
        exit 1
    fi
    psql_q -c "INSERT INTO exchange_service.exchange_reservations
            (reservation_id, user_id, wallet_id, idempotency_key, from_currency, to_currency, from_amount,
             target_rate, rate_unit, status, expires_at, created_at, updated_at)
        SELECT gen_random_uuid(), ('00000000-0000-4000-9000-' || lpad(i::text, 12, '0'))::uuid,
               '33333333-3333-4333-8333-333333333333', 'load-reservation-${LABEL}-${k6_started}-' || i, 'KRW', 'JPY', 100000,
               99999, 100, 'WAITING', now() + interval '1 day', now(), now()
        FROM generate_series(1, ${RESERVATIONS}) AS i" >/dev/null
    reservations_inserted_at=$(date +%s)
    echo "      k6가 요청을 시작해 대기 예약 ${RESERVATIONS}건을 넣었습니다: $(date -d @"$reservations_inserted_at" +%T) (k6 명령 뒤 $((reservations_inserted_at - k6_started))초)"
fi

wait "$k6_pid"
k6_ended=$(date +%s)
# k6가 끝났으니 수집기를 멈춘다
docker compose exec -T app touch /tmp/stop-sampler >/dev/null 2>&1 || true
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
echo "      측정 구간 $(date -d @"$measure_from" +%T) ~ $(date -d @"$measure_to" +%T) (k6 시작 명령 $(date -d @"$k6_started" +%T), 끝 $(date -d @"$k6_ended" +%T))"
wait "$sampler_pid" 2>/dev/null || true

echo "[5/5] 서버 지표·주문 상태 정리"
# 이번 실행에서 만든 주문의 상태별 수(예열 포함)와, 측정 구간에 신청한 주문의 상태별 수
orders_all=$(psql_q -F ' ' -c "SELECT status, count(*) FROM exchange_service.exchange_orders GROUP BY status ORDER BY status" | awk '{ printf "%s\"%s\": %d", (n++ ? ", " : ""), $1, $2 }')
orders_measure=$(psql_q -F ' ' -c "SELECT status, count(*) FROM exchange_service.exchange_orders WHERE requested_at >= to_timestamp(${measure_from}) AND requested_at < to_timestamp(${measure_to}) GROUP BY status ORDER BY status" | awk '{ printf "%s\"%s\": %d", (n++ ? ", " : ""), $1, $2 }')
# 환율 수집 간격(mock, 엔화): 측정 구간에서 이어진 두 수집 사이의 가장 긴 틈과, 마지막 수집부터 구간 끝까지.
# 원래 5초마다 수집한다. 수집 스레드가 예약 체결(원장 호출)에 붙잡히면 이 틈이 벌어진다
collection=$(psql_q -F ' ' -c "WITH c AS (SELECT collected_at FROM exchange_service.exchange_rates
        WHERE source = 'MOCK' AND target_currency = 'JPY' AND collected_at >= to_timestamp(${measure_from}) AND collected_at < to_timestamp(${measure_to}))
    SELECT count(*), coalesce(round(greatest(
        (SELECT max(extract(epoch FROM gap)) FROM (SELECT collected_at - lag(collected_at) OVER (ORDER BY collected_at) AS gap FROM c) g),
        extract(epoch FROM to_timestamp(${measure_to}) - max(collected_at)),
        extract(epoch FROM min(collected_at) - to_timestamp(${measure_from})))::numeric, 1), ${MEASURE_SECONDS}) FROM c")
collections=${collection% *}
collection_max_gap=${collection#* }
reservations=$(psql_q -F ' ' -c "SELECT status, count(*) FROM exchange_service.exchange_reservations GROUP BY status ORDER BY status" | awk '{ printf "%s\"%s\": %d", (n++ ? ", " : ""), $1, $2 }')

awk -F'\t' -v from="$measure_from" -v to="$measure_to" -v label="$LABEL" -v delay="$LEDGER_DELAY_MS" \
    -v orderRate="$ORDER_RATE" -v rateRate="$RATE_RATE" -v timeout="$TIMEOUT" \
    -v pcBefore="$pc_before" -v pcAfter="$pc_after" -v standby="$standby" \
    -v ordersAll="$orders_all" -v ordersMeasure="$orders_measure" \
    -v k6Script="$K6_SCRIPT" -v seeded="$RESERVATIONS" -v reservations="$reservations" -v insertedAt="$reservations_inserted_at" \
    -v collections="$collections" -v collectionMaxGap="$collection_max_gap" '
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
        printf "  \"conditions\": {\"ledgerDelayMs\": %d, \"k6Script\": \"%s\", \"orderRate\": %d, \"rateRate\": %d, \"seededReservations\": %d, \"reservationsInsertedAt\": %s, \"timeout\": \"%s\", \"pcBefore\": %s, \"pcAfter\": %s, \"standbyEntries\": %s},\n", delay, k6Script, orderRate, rateRate, seeded, insertedAt, timeout, pcBefore, pcAfter, standby
        printf "  \"samples\": %d,\n", n
        printf "  \"tomcatThreads\": {\"busyAvg\": %.1f, \"busyMax\": %d, \"max\": %d, \"saturatedSeconds\": %d},\n", busy / n, busyMax, maxThreads, saturated
        printf "  \"hikariPending\": {\"avg\": %.2f, \"max\": %d},\n", pend / n, pendMax
        printf "  \"hikariActiveAvg\": %.2f,\n", act / n
        printf "  \"cpuPercent\": {\"systemAvg\": %.1f, \"appAvg\": %.1f},\n", sys / n * 100, proc / n * 100
        printf "  \"orders\": {\"all\": {%s}, \"measure\": {%s}},\n", ordersAll, ordersMeasure
        # 환율 수집(mock, 엔화): 측정 구간의 수집 횟수와 가장 긴 수집 간격(초, 원래 5초)
        printf "  \"rateCollection\": {\"count\": %d, \"maxGapSeconds\": %s},\n", collections, collectionMaxGap
        printf "  \"reservations\": {%s}\n", reservations
        printf "}\n"
    }' "$samples" >"$summary.tmp" || {
        rm -f "$summary.tmp" "$k6_result"
        exit 1
    }
mv "$summary.tmp" "$summary"
trap - EXIT

cat "$summary"
