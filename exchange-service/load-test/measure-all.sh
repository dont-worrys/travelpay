#!/usr/bin/env bash
# 동시 요청 50 / 100 / 300 단계를 단계마다 여러 번(기본 3번) 측정하고, 단계별 중앙값 표를 만듭니다.
# 한 번의 측정은 measure.sh가 합니다(측정마다 앱 재시작 → 이력 적재 → k6 → 지표 정리).
#
# 사용법: [SCRIPT=<k6 스크립트>] ./measure-all.sh <LABEL> [REPS]
#   예: ./measure-all.sh db                               → 환율 조회 (기본 SCRIPT=rate-query.js)
#       SCRIPT=quote-issue.js ./measure-all.sh quote-db   → 견적 발급
#   결과: results/<LABEL>-r1-vus50.json ... + results/<LABEL>-summary.md (중앙값 표, 마크다운)
#
# 실행마다 결과가 조금씩 다르므로 한 번 값이 아니라 중앙값을 씁니다(가장 튀는 값 하나에 휘둘리지 않음).
set -euo pipefail

LABEL=${1:?LABEL(db/redis)을 주세요}
REPS=${2:-3}
STAGES=${STAGES:-"50 100 300"}
export SCRIPT=${SCRIPT:-rate-query.js}   # measure.sh에 그대로 넘긴다

cd "$(dirname "$0")"
summary="results/${LABEL}-summary.md"
# 앞 실행의 요약이 남아 이번 실행 결과로 오인되지 않게 먼저 지운다 (모든 회차가 성공해야 새로 생김)
rm -f "$summary"

# JSON에서 숫자 하나 꺼내기: value <파일> <키>  (키가 여러 번 나오면 첫 번째. avg는 객체 이름과 함께 찾음)
value() {
    grep -o "\"$2\": *[0-9.]*" "$1" | head -1 | grep -o '[0-9.]*$'
}
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
        # 견적 발급 검산: 성공 응답(예열 포함) 수만큼 정확히 견적이 저장됐는지. 다르면 응답과 저장이 어긋난 것이라 멈춘다
        if [ "$SCRIPT" = quote-issue.js ]; then
            ok_all=$(value "$run" okAll)
            saved=$(nested "results/${LABEL}-r${rep}-vus${vus}-db.json" quoteRows all)
            if [ "$ok_all" != "$saved" ]; then
                echo "견적 검산 실패: 성공 응답 ${ok_all}건, 저장된 견적 ${saved}건 (${run})" >&2
                exit 1
            fi
        fi
    done
done

{
    # 스크립트별 열: 환율 조회는 오래된 환율 응답 비율, 견적 발급은 저장된 견적 수 / 성공 응답 수 (예열 포함, 회차 합. 같아야 함)
    if [ "$SCRIPT" = quote-issue.js ]; then extra_column="견적 저장 / 성공 응답 (합)"; else extra_column="오래된 응답 %%"; fi
    printf "| 동시 요청 | TPS | P50 | P95 | P99 | 연결 대기 | 연결 얻는 ms | DB 활성 세션 | 캐시 hit %% | ${extra_column} | 실패율 %% (최대) | 4xx / 5xx / 응답 없음 (합) | CPU 전체 %% | 앱 CPU %% | TPS(회차별) |\n"
    printf "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n"
    for vus in $STAGES; do
        k6=() db=()
        for rep in $(seq 1 "$REPS"); do
            k6+=("results/${LABEL}-r${rep}-vus${vus}.json")
            db+=("results/${LABEL}-r${rep}-vus${vus}-db.json")
        done
        tps=$(for f in "${k6[@]}"; do value "$f" successTps; done)
        if [ "$SCRIPT" = quote-issue.js ]; then
            saved_sum=$(for f in "${db[@]}"; do nested "$f" quoteRows all; done | awk '{ s += $1 } END { print s + 0 }')
            extra="${saved_sum} / $(sum okAll "${k6[@]}")"
        else
            extra=$(for f in "${k6[@]}"; do
                awk -v s="$(value "$f" staleResponses)" -v r="$(value "$f" requests)" 'BEGIN { printf "%.2f\n", r ? s / r * 100 : 0 }'
            done | median)
        fi
        # 캐시 hit 비율 = hit / (hit + miss + error). 캐시를 쓰지 않는 jar면 "-"
        cache_hit=$(for f in "${db[@]}"; do
            h=$(nested "$f" cacheLookups hit || true)
            if [ -n "$h" ]; then
                awk -v h="$h" -v m="$(nested "$f" cacheLookups miss)" -v e="$(nested "$f" cacheLookups error)" \
                    'BEGIN { t = h + m + e; printf "%.2f\n", t ? h / t * 100 : 0 }'
            fi
        done | median)
        printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" "$vus" \
            "$(echo "$tps" | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p50; done | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p95; done | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p99; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" hikariPending avg; done | median)" \
            "$(for f in "${db[@]}"; do value "$f" acquireMsAvg; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" dbActiveSessions avg; done | median)" \
            "$cache_hit" \
            "$extra" \
            "$(for f in "${k6[@]}"; do value "$f" failureRate; done | sort -n | tail -1)" \
            "$(sum '4xx' "${k6[@]}") / $(sum '5xx' "${k6[@]}") / $(sum noResponse "${k6[@]}")" \
            "$(for f in "${db[@]}"; do nested "$f" cpuPercent systemAvg; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" cpuPercent appAvg; done | median)" \
            "$(echo "$tps" | paste -sd/)"
    done
} >"$summary"

cat "$summary"
