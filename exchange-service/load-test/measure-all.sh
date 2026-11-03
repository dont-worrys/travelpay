#!/usr/bin/env bash
# 동시 요청 50 / 100 / 300 단계를 단계마다 여러 번(기본 3번) 측정하고, 단계별 중앙값 표를 만듭니다.
# 한 번의 측정은 measure.sh가 합니다(측정마다 앱 재시작 → 이력 적재 → k6 → 지표 정리).
#
# 사용법: ./measure-all.sh <LABEL> [REPS]
#   예: ./measure-all.sh db      → results/db-r1-vus50.json ... + results/db-summary.md (중앙값 표, 마크다운)
#
# 실행마다 결과가 조금씩 다르므로 한 번 값이 아니라 중앙값을 씁니다(가장 튀는 값 하나에 휘둘리지 않음).
set -euo pipefail

LABEL=${1:?LABEL(db/redis)을 주세요}
REPS=${2:-3}
STAGES=${STAGES:-"50 100 300"}

cd "$(dirname "$0")"
summary="results/${LABEL}-summary.md"

# JSON에서 숫자 하나 꺼내기: value <파일> <키>  (키가 여러 번 나오면 첫 번째. avg는 객체 이름과 함께 찾음)
value() {
    grep -o "\"$2\": *[0-9.]*" "$1" | head -1 | grep -o '[0-9.]*$'
}

for vus in $STAGES; do
    for rep in $(seq 1 "$REPS"); do
        echo "=== ${LABEL} 동시 요청 ${vus}개, ${rep}/${REPS}회"
        ./measure.sh "${LABEL}-r${rep}" "$vus" >/dev/null
        # 실패한 요청이 하나라도 있으면 그 측정은 무효라 중앙값에 넣지 않고 멈춘다 (원인부터 찾음).
        # k6 기준값은 측정을 멈추지 않게 항상 통과하도록 두었으므로 여기서 확인한다
        run="results/${LABEL}-r${rep}-vus${vus}.json"
        failed=$(( $(value "$run" '4xx') + $(value "$run" '5xx') + $(value "$run" noResponse) ))
        if [ "$failed" -gt 0 ]; then
            echo "실패한 요청 ${failed}건 (4xx/5xx/응답 없음): ${run}. 원인을 확인한 뒤 다시 측정합니다." >&2
            exit 1
        fi
    done
done
nested() {   # nested <파일> <객체> <키>  예: nested x.json hikariPending avg (k6 JSON은 여러 줄이라 줄바꿈을 지우고 찾음)
    tr -d '\n' <"$1" | grep -o "\"$2\": *{[^}]*}" | grep -o "\"$3\": *[0-9.]*" | grep -o '[0-9.]*$'
}
median() {   # 표준 입력의 숫자들(한 줄에 하나)의 중앙값
    sort -n | awk '{ v[NR] = $1 } END { if (NR == 0) { print "-"; exit } print (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2 }'
}
sum() {      # sum <키> <파일...>: 회차별 값의 합 (실패 건수처럼 중앙값이 아니라 전부 보여야 하는 값)
    local key=$1
    shift
    for f in "$@"; do value "$f" "$key"; done | awk '{ s += $1 } END { print s + 0 }'
}

{
    printf "| 동시 요청 | TPS | P50 | P95 | P99 | 연결 대기 | 연결 얻는 ms | 오래된 응답 %% | 실패율 %% (최대) | 4xx / 5xx / 응답 없음 (합) | CPU 전체 %% | 앱 CPU %% | TPS(회차별) |\n"
    printf "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n"
    for vus in $STAGES; do
        k6=() db=()
        for rep in $(seq 1 "$REPS"); do
            k6+=("results/${LABEL}-r${rep}-vus${vus}.json")
            db+=("results/${LABEL}-r${rep}-vus${vus}-db.json")
        done
        tps=$(for f in "${k6[@]}"; do value "$f" successTps; done)
        stale=$(for f in "${k6[@]}"; do
            awk -v s="$(value "$f" staleResponses)" -v r="$(value "$f" requests)" 'BEGIN { printf "%.2f\n", r ? s / r * 100 : 0 }'
        done)
        printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" "$vus" \
            "$(echo "$tps" | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p50; done | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p95; done | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p99; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" hikariPending avg; done | median)" \
            "$(for f in "${db[@]}"; do value "$f" acquireMsAvg; done | median)" \
            "$(echo "$stale" | median)" \
            "$(for f in "${k6[@]}"; do value "$f" failureRate; done | sort -n | tail -1)" \
            "$(sum '4xx' "${k6[@]}") / $(sum '5xx' "${k6[@]}") / $(sum noResponse "${k6[@]}")" \
            "$(for f in "${db[@]}"; do nested "$f" cpuPercent systemAvg; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" cpuPercent appAvg; done | median)" \
            "$(echo "$tps" | paste -sd/)"
    done
} >"$summary"

cat "$summary"
