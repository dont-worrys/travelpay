#!/usr/bin/env bash
# 사용자 수 한 단계를 측정합니다: 이력 적재 → k6 실행(그동안 DB 지표를 1초마다 기록) → 결과 정리.
# 측정 DB와 환전 서비스(mock 모드)를 먼저 띄워 둡니다. 자세한 순서는 README.md를 봅니다.
#
# 사용법: ./measure.sh <LABEL> <VUS> [HOURS]
#   예: ./measure.sh db 50      → results/db-vus50.json(k6) + results/db-vus50-db.json(DB 지표)
#
# DB 지표는 측정 구간(예열 + k6 시작 여유 2초 이후 ~ k6 종료)의 1초 간격 표본으로 계산합니다.
#   연결 대기·사용 중 연결: Actuator hikaricp_connections_pending / _active의 평균·최대
#   연결 얻는 시간: hikaricp_connections_acquire_seconds 합계·횟수의 구간 차이로 평균, 최댓값은 _max의 최대
#   DB 활성 세션: pg_stat_activity에서 state=active인 세션 수(이 조회 자신은 제외)의 평균·최대
#   환율 조회 쿼리 수: pg_stat_statements의 exchange_rates SELECT 호출 수 합 (예열·수집기 포함, 검산용)
set -euo pipefail

LABEL=${1:?LABEL(db/redis)을 주세요}
VUS=${2:?VUS(50/100/300)를 주세요}
HOURS=${3:-24}
WARMUP_SECONDS=${WARMUP_SECONDS:-15}
# Actuator는 별도 관리 포트로 띄운다(README). 지표 요청이 부하 요청 뒤에 줄 서지 않게
MANAGEMENT_URL=${MANAGEMENT_URL:-http://localhost:18085}

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 컨테이너 안 경로(/load-test/...)를 바꾸지 않게
mkdir -p results
hikari_samples="results/${LABEL}-vus${VUS}-hikari.tsv"
session_samples="results/${LABEL}-vus${VUS}-sessions.tsv"
summary="results/${LABEL}-vus${VUS}-db.json"

psql_q() {
    docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -qAt "$@"
}

echo "[1/3] 환율 이력 적재 (hours=${HOURS})"
psql_q -v hours="${HOURS}" -f /load-test/seed-rate-history.sql >/dev/null

echo "[2/3] k6 실행 (VUS=${VUS}), DB 지표 1초 간격 기록"
# 커넥션 풀 지표: curl로 1초마다. 한 번의 awk로 시각(systime)과 지표 다섯 개를 함께 뽑는다
# (지표마다 프로세스를 띄우면 Windows에서 한 바퀴가 2초 넘게 걸려 표본이 빠짐)
(
    set +e   # 시간 초과 한 번에 루프가 끝나지 않게 (위의 set -e가 여기에도 적용되므로 끈다)
    while true; do
        curl -s --max-time 2 "${MANAGEMENT_URL}/actuator/prometheus" | awk '
            $1 ~ /^hikaricp_connections_pending\{/ { p = $2 }
            $1 ~ /^hikaricp_connections_active\{/ { a = $2 }
            $1 ~ /^hikaricp_connections_acquire_seconds_count\{/ { c = $2 }
            $1 ~ /^hikaricp_connections_acquire_seconds_sum\{/ { s = $2 }
            $1 ~ /^hikaricp_connections_acquire_seconds_max\{/ { m = $2 }
            END { printf "%d\t%s\t%s\t%s\t%s\t%s\n", systime(), p, a, c, s, m }'
        sleep 1
    done
) >"$hikari_samples" &
hikari_pid=$!
# DB 활성 세션: psql 하나를 띄워 두고 \watch로 1초마다 (매번 새로 띄우면 느려서 표본이 빠짐).
# 횟수(c)를 정해 측정이 끝날 즈음 스스로 끝나게 한다 (Windows에서는 백그라운드 docker를 kill로 멈출 수 없음)
watch_count=$((WARMUP_SECONDS + 60 + 10))
printf "SELECT extract(epoch FROM now())::bigint, count(*) FROM pg_stat_activity WHERE datname = 'travelpay' AND state = 'active' AND pid <> pg_backend_pid()\n\\\\watch i=1 c=%d\n" "$watch_count" \
    | docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -qAt -F $'\t' >"$session_samples" &
session_pid=$!
trap 'kill $hikari_pid 2>/dev/null || true' EXIT

measure_from=$(($(date +%s) + WARMUP_SECONDS + 2))
docker compose run --rm -e VUS="${VUS}" -e LABEL="${LABEL}" -e WARMUP="${WARMUP_SECONDS}s" \
    k6 run --quiet /scripts/rate-query.js
measure_to=$(date +%s)

kill "$hikari_pid" 2>/dev/null || true
wait "$session_pid" 2>/dev/null || true   # psql은 정해진 횟수를 채우면 스스로 끝남

echo "[3/3] DB 지표 정리"
rate_selects=$(psql_q -c "SELECT coalesce(sum(calls), 0) FROM public.pg_stat_statements WHERE query ILIKE 'select%' AND query ILIKE '%exchange_rates%'")

# 측정 구간 표본만 골라 평균·최대를 낸다
awk -F'\t' -v from="$measure_from" -v to="$measure_to" -v selects="$rate_selects" \
    -v label="$LABEL" -v vus="$VUS" '
    FNR == 1 { file++ }
    $1 < from || $1 > to || $2 == "" { next }
    file == 1 {
        n++; pend += $2; act += $3
        if ($2 > pendMax) pendMax = $2
        if ($3 > actMax) actMax = $3
        if ($6 > acqMax) acqMax = $6
        if (n == 1) { c0 = $4; s0 = $5 }
        c1 = $4; s1 = $5
    }
    file == 2 {
        m++; ses += $2
        if ($2 > sesMax) sesMax = $2
    }
    END {
        acqAvgMs = (c1 > c0) ? (s1 - s0) / (c1 - c0) * 1000 : 0
        printf "{\n"
        printf "  \"label\": \"%s\",\n  \"vus\": %d,\n", label, vus
        printf "  \"samples\": {\"hikari\": %d, \"sessions\": %d},\n", n, m
        printf "  \"hikariPending\": {\"avg\": %.2f, \"max\": %d},\n", (n ? pend / n : 0), pendMax
        printf "  \"hikariActive\": {\"avg\": %.2f, \"max\": %d},\n", (n ? act / n : 0), actMax
        printf "  \"acquireMs\": {\"avg\": %.3f, \"max\": %.3f},\n", acqAvgMs, acqMax * 1000
        printf "  \"dbActiveSessions\": {\"avg\": %.2f, \"max\": %d},\n", (m ? ses / m : 0), sesMax
        printf "  \"rateSelectCalls\": %d\n", selects
        printf "}\n"
    }' "$hikari_samples" "$session_samples" >"$summary"

cat "$summary"
