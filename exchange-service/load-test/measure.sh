#!/usr/bin/env bash
# 동시 요청 수 한 단계를 측정합니다: 앱 재시작 → 이력 적재 → k6 실행(그동안 DB·커넥션 지표를 1초마다 기록) → 결과 정리.
# 측정 DB·앱·k6는 compose.yaml로 같은 Docker 네트워크에 띄웁니다(jar는 먼저 빌드). 자세한 순서는 README.md를 봅니다.
#
# 사용법: [SCRIPT=<k6 스크립트>] ./measure.sh <LABEL> <VUS> [HOURS]
#   예: ./measure.sh db 50                               → 환율 조회 (기본 SCRIPT=rate-query.js)
#       SCRIPT=quote-issue.js ./measure.sh quote-db 50   → 견적 발급
#   결과: results/<LABEL>-vus<VUS>.json(k6) + results/<LABEL>-vus<VUS>-db.json(DB 지표)
#
# DB 지표는 측정 구간(k6가 실제로 시작한 시각 + 예열 ~ 그로부터 측정 시간)의 1초 간격 표본으로 계산합니다.
# 표본이 없거나 측정 구간 전체를 덮지 못하면(지표를 못 읽음, 수집기가 먼저 끝남) 0이나 일부 구간으로 채우지 않고 실패로 끝냅니다.
#   연결 대기·사용 중 연결: Actuator hikaricp_connections_pending / _active의 평균·최대
#   연결 얻는 시간: hikaricp_connections_acquire_seconds 합계·횟수의 구간 차이로 평균 (_max는 앞 실행 값을 들고 있어 쓰지 않음)
#   DB 활성 세션: pg_stat_activity에서 state=active인 세션 수(이 조회 자신은 제외)의 평균·최대
#   환율 조회 쿼리 수: pg_stat_statements의 exchange_rates SELECT 호출 수 합 (예열·수집기 포함, 검산용)
#   견적 행 수: exchange_quotes 행 수 (all = 예열 포함 전체, measure = created_at이 측정 구간 안). 환율 조회 측정이면 0
#   캐시 조회 수: Actuator exchange_rate_cache_lookups_total{result=hit/miss/error}의 구간 차이 (통화 하나당 1). 캐시를 쓰지 않는 jar면 null
set -euo pipefail

LABEL=${1:?LABEL(db/redis)을 주세요}
VUS=${2:?VUS(50/100/300)를 주세요}
HOURS=${3:-24}
WARMUP_SECONDS=${WARMUP_SECONDS:-15}
MEASURE_SECONDS=${MEASURE_SECONDS:-60}
SCRIPT=${SCRIPT:-rate-query.js}

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 컨테이너 안 경로(/load-test/...)를 바꾸지 않게
mkdir -p results
if [ ! -f "$SCRIPT" ]; then
    echo "k6 스크립트가 없습니다: $SCRIPT (rate-query.js / quote-issue.js)" >&2
    exit 1
fi
hikari_samples="results/${LABEL}-vus${VUS}-hikari.tsv"
session_samples="results/${LABEL}-vus${VUS}-sessions.tsv"
summary="results/${LABEL}-vus${VUS}-db.json"
# 앞 실행의 결과가 남아 이번 실행 결과로 오인되지 않게 먼저 지운다 (이번 실행이 성공해야 다시 생김)
rm -f "$summary" "results/${LABEL}-vus${VUS}.json"

psql_q() {
    docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -qAt "$@"
}

# 측정마다 앱을 새로 띄운다. 앞 측정의 JIT·커넥션·메모리 상태가 다음 측정에 넘어가지 않게
echo "[1/4] 환전 서비스 재시작 (준비될 때까지 대기)"
docker compose --profile app up -d --force-recreate --wait app >/dev/null 2>&1

echo "[2/4] 환율 이력 적재 (hours=${HOURS}), 캐시 Redis 비우기"
psql_q -v hours="${HOURS}" -f /load-test/seed-rate-history.sql >/dev/null
# 적재 SQL이 환율 표를 비우고 새로 채우므로, 앱이 시작하며 캐시에 넣은 환율은 DB에 없는 행이 된다.
# 그대로 두면 다음 수집(최대 5초) 전까지 그 환율로 견적을 내다 외래 키에 막힌다. 그래서 적재 직후 캐시를 비운다
docker compose exec -T measure-redis redis-cli FLUSHALL >/dev/null

echo "[3/4] k6 실행 (${SCRIPT}, VUS=${VUS}), DB 지표 1초 간격 기록"
# 지표는 Docker 네트워크 안에서 읽는다(앱 컨테이너 안에서 curl). PC에서 읽으면 포트 전달을 지나 표본이 빠짐.
# 수집기는 정해진 시간이 지나면 스스로 끝난다(Windows에서는 백그라운드 docker를 kill로 멈출 수 없음).
collect_seconds=$((WARMUP_SECONDS + MEASURE_SECONDS + 15))   # k6 컨테이너가 뜨는 시간 여유
# 커넥션 풀·CPU 지표: 1초마다 "ts <시각>" 줄과 Actuator 지표를 내보내고, PC 쪽 awk가 한 줄 표본으로 묶는다
docker compose exec -T app sh -c "end=\$((\$(date +%s) + ${collect_seconds})); while [ \$(date +%s) -lt \$end ]; do echo \"ts \$(date +%s)\"; curl -s --max-time 2 http://localhost:18085/actuator/prometheus; sleep 1; done" \
    | awk '
        function flush() { if (ts != "") printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts, p, a, c, s, sc, pc, ch, cm, ce; p = a = c = s = sc = pc = ch = cm = ce = "" }
        $1 == "ts" { flush(); ts = $2; next }
        $1 ~ /^hikaricp_connections_pending\{/ { p = $2 }
        $1 ~ /^hikaricp_connections_active\{/ { a = $2 }
        $1 ~ /^hikaricp_connections_acquire_seconds_count\{/ { c = $2 }
        $1 ~ /^hikaricp_connections_acquire_seconds_sum\{/ { s = $2 }
        $1 == "system_cpu_usage" { sc = $2 }
        $1 == "process_cpu_usage" { pc = $2 }
        # 환율 캐시 조회 결과(누적). Redis를 쓰지 않는 jar에는 없어 빈 칸으로 남는다
        $1 ~ /^exchange_rate_cache_lookups_total\{.*result="hit"/ { ch = $2 }
        $1 ~ /^exchange_rate_cache_lookups_total\{.*result="miss"/ { cm = $2 }
        $1 ~ /^exchange_rate_cache_lookups_total\{.*result="error"/ { ce = $2 }
        END { flush() }' >"$hikari_samples" &
hikari_pid=$!
# DB 활성 세션: psql 하나를 띄워 두고 \watch로 1초마다 (매번 새로 띄우면 느려서 표본이 빠짐). 횟수(c)를 정해 스스로 끝나게 한다
printf "SELECT extract(epoch FROM now())::bigint, count(*) FROM pg_stat_activity WHERE datname = 'travelpay' AND state = 'active' AND pid <> pg_backend_pid()\n\\watch i=1 c=%d\n" "$collect_seconds" \
    | docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -qAt -F $'\t' >"$session_samples" &
session_pid=$!

docker compose run --rm -e VUS="${VUS}" -e LABEL="${LABEL}" -e WARMUP="${WARMUP_SECONDS}s" \
    -e DURATION="${MEASURE_SECONDS}s" k6 run --quiet "/scripts/${SCRIPT}"
k6_ended=$(date +%s)

# 측정 구간은 k6가 실제로 테스트를 시작한 시각에서 계산한다. 컨테이너 생성·이미지 내려받기에 걸린 시간과 상관없이
# 예열 표본이 섞이지 않게 하려는 것이다. 시작 시각 = k6가 끝난 시각 - k6가 실제로 돈 시간(results JSON)
run_ms=$(grep -o '"testRunDurationMs": [0-9]*' "results/${LABEL}-vus${VUS}.json" | grep -o '[0-9]*$')
if [ -z "$run_ms" ]; then
    echo "k6 결과에서 testRunDurationMs를 찾지 못했습니다: results/${LABEL}-vus${VUS}.json" >&2
    exit 1
fi
measure_from=$((k6_ended - (run_ms + 999) / 1000 + WARMUP_SECONDS))
measure_to=$((measure_from + MEASURE_SECONDS))

# 두 수집기는 정해진 시간을 채우면 스스로 끝남
wait "$hikari_pid" 2>/dev/null || true
wait "$session_pid" 2>/dev/null || true

echo "[4/4] DB 지표 정리"
rate_selects=$(psql_q -c "SELECT coalesce(sum(calls), 0) FROM public.pg_stat_statements WHERE query ILIKE 'select%' AND query ILIKE '%exchange_rates%'")
# 견적 행 수. 측정 직전 적재 SQL이 견적 테이블을 비우므로 all = 이번 실행(예열 포함)에서 만든 견적이다
quote_rows=$(psql_q -F ' ' -c "SELECT count(*), count(*) FILTER (WHERE created_at >= to_timestamp(${measure_from}) AND created_at < to_timestamp(${measure_to})) FROM exchange_service.exchange_quotes")
quote_all=${quote_rows% *}
quote_measure=${quote_rows#* }

# 측정 구간 표본만 골라 평균·최대를 낸다
awk -F'\t' -v from="$measure_from" -v to="$measure_to" -v selects="$rate_selects" \
    -v quoteAll="$quote_all" -v quoteMeasure="$quote_measure" -v label="$LABEL" -v vus="$VUS" '
    FNR == 1 { file++ }
    $1 < from || $1 > to || $2 == "" { next }
    # 커넥션 풀 표본은 지표 6개가 모두 있어야 쓴다. 응답이 중간에 끊겨 일부만 오면 빠진 값이 0으로 더해져
    # 평균이 틀어지므로 그 행은 버린다 (버린 만큼 틈이 생겨 아래 틈 검사에 걸림)
    file == 1 && ($3 == "" || $4 == "" || $5 == "" || $6 == "" || $7 == "") { next }
    file == 1 {
        if (n == 0) h0 = $1
        else if ($1 - h1 > hGap) hGap = $1 - h1   # 연속된 표본 사이 가장 긴 틈
        h1 = $1
        n++; pend += $2; act += $3; sys += $6; proc += $7
        if ($6 > sysMax) sysMax = $6
        if ($2 > pendMax) pendMax = $2
        if ($3 > actMax) actMax = $3
        if (n == 1) { c0 = $4; s0 = $5 }
        c1 = $4; s1 = $5
        if ($8 != "") {
            if (k == 0) { hit0 = $8; miss0 = $9; err0 = $10 }
            k++; hit1 = $8; miss1 = $9; err1 = $10
        }
    }
    file == 2 {
        if (m == 0) d0 = $1
        else if ($1 - d1 > dGap) dGap = $1 - d1
        d1 = $1
        m++; ses += $2
        if ($2 > sesMax) sesMax = $2
    }
    END {
        # 표본이 없으면 0으로 채운 결과를 쓰지 않고, 어느 지표를 못 모았는지 알린 뒤 실패한다
        if (n == 0 || m == 0) {
            if (n == 0) print "커넥션 풀 표본이 없습니다: 앱 컨테이너 안에서 Actuator 지표를 읽지 못함" > "/dev/stderr"
            if (m == 0) print "DB 세션 표본이 없습니다: pg_stat_activity 조회 실패" > "/dev/stderr"
            exit 1
        }
        # 표본이 측정 구간의 처음부터 끝까지 빈틈없이 있어야 한다. k6 컨테이너가 늦게 떠 수집기가 먼저 끝나거나
        # 중간에 지표 읽기가 실패하면, 구간 일부만으로 평균을 내게 되므로 실패한다.
        # 허용 3초: 정상 측정(2026-11-03, 9회)의 표본 간격은 최대 2초. 읽기를 한 번 놓치면 약 4초가 된다
        gap = 3
        if (h0 > from + gap || h1 < to - gap || d0 > from + gap || d1 < to - gap) {
            printf "표본이 측정 구간(%d~%d)을 다 덮지 못했습니다: 커넥션 풀 %d~%d, DB 세션 %d~%d\n", from, to, h0, h1, d0, d1 > "/dev/stderr"
            exit 1
        }
        if (hGap > gap || dGap > gap) {
            printf "측정 구간 중간에 표본이 빠졌습니다: 가장 긴 틈 커넥션 풀 %d초, DB 세션 %d초 (허용 %d초)\n", hGap, dGap, gap > "/dev/stderr"
            exit 1
        }
        acqAvgMs = (c1 > c0) ? (s1 - s0) / (c1 - c0) * 1000 : 0
        printf "{\n"
        printf "  \"label\": \"%s\",\n  \"vus\": %d,\n", label, vus
        printf "  \"samples\": {\"hikari\": %d, \"sessions\": %d},\n", n, m
        printf "  \"hikariPending\": {\"avg\": %.2f, \"max\": %d},\n", (n ? pend / n : 0), pendMax
        printf "  \"hikariActive\": {\"avg\": %.2f, \"max\": %d},\n", (n ? act / n : 0), actMax
        printf "  \"acquireMsAvg\": %.3f,\n", acqAvgMs
        # PC 전체(system)와 환전 서비스 JVM(app)의 CPU 사용률. 한 PC에서 k6·Docker·DB와 CPU를 나눠 쓰는지 본다
        printf "  \"cpuPercent\": {\"systemAvg\": %.1f, \"systemMax\": %.1f, \"appAvg\": %.1f},\n", sys / n * 100, sysMax * 100, proc / n * 100
        printf "  \"dbActiveSessions\": {\"avg\": %.2f, \"max\": %d},\n", (m ? ses / m : 0), sesMax
        printf "  \"quoteRows\": {\"all\": %d, \"measure\": %d},\n", quoteAll, quoteMeasure
        # 측정 구간 동안 늘어난 캐시 조회 수 (통화 하나당 1). 캐시를 쓰지 않는 jar면 null
        if (k > 0) printf "  \"cacheLookups\": {\"hit\": %d, \"miss\": %d, \"error\": %d},\n", hit1 - hit0, miss1 - miss0, err1 - err0
        else printf "  \"cacheLookups\": null,\n"
        printf "  \"rateSelectCalls\": %d\n", selects
        printf "}\n"
    }' "$hikari_samples" "$session_samples" >"$summary.tmp" || {
        # 검사에 실패한 측정은 k6 결과도 지운다. 남겨 두면 실패한 측정의 응답 결과를 유효한 결과로 오인할 수 있음
        rm -f "$summary.tmp" "results/${LABEL}-vus${VUS}.json"
        exit 1
    }
# 검사를 모두 통과했을 때만 결과 파일을 만든다 (실패하면 빈 결과 파일도 남기지 않음)
mv "$summary.tmp" "$summary"

cat "$summary"
