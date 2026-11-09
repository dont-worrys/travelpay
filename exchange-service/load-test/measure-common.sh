#!/usr/bin/env bash
# measure-all.sh·measure-compare.sh가 함께 쓰는 함수입니다. 직접 실행하지 않고 source로 불러 씁니다.
# 결과 JSON에서 값 꺼내기, 한 번의 측정이 유효한지 검사하기, 단계별 중앙값 표 만들기를 담습니다.

# JSON에서 숫자 하나 꺼내기: value <파일> <키>  (키가 여러 번 나오면 첫 번째. 없으면 빈 값)
value() {
    grep -o "\"$2\": *[0-9.]*" "$1" | head -1 | grep -o '[0-9.]*$' || true
}
nested() {   # nested <파일> <객체> <키>  예: nested x.json hikariPending avg (k6 JSON은 여러 줄이라 줄바꿈을 지우고 찾음)
    tr -d '\n' <"$1" | grep -o "\"$2\": *{[^}]*}" | grep -o "\"$3\": *[0-9.]*" | grep -o '[0-9.]*$' || true
}
median() {   # 표준 입력의 숫자들(한 줄에 하나)의 중앙값
    sort -n | awk '{ v[NR] = $1 } END { if (NR == 0) { print "-"; exit } print (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2 }'
}
sum() {      # sum <키> <파일...>: 회차별 값의 합 (실패 건수처럼 중앙값이 아니라 전부 보여야 하는 값)
    local key=$1
    shift
    for f in "$@"; do value "$f" "$key"; done | awk '{ s += $1 } END { print s + 0 }'
}

# 한 번의 측정이 유효한지 검사: check_run <측정 LABEL> <VUS>  (SCRIPT 환경 변수로 스크립트 구분)
# 실패한 요청(4xx·5xx·응답 없음)이나 불완전 응답(지원 통화가 빠진 200)이 하나라도 있으면 무효라 멈춘다 (원인부터 찾음).
# k6 기준값은 측정을 멈추지 않게 항상 통과하도록 두었으므로 여기서 확인한다.
check_run() {
    local run="results/$1-vus$2.json"
    local failed incomplete
    failed=$(( $(value "$run" '4xx') + $(value "$run" '5xx') + $(value "$run" noResponse) ))
    incomplete=$(value "$run" incompleteResponses)
    if [ "$failed" -gt 0 ] || [ "${incomplete:-0}" -gt 0 ]; then
        echo "무효 측정: 실패한 요청 ${failed}건(4xx/5xx/응답 없음), 불완전 응답 ${incomplete:-0}건: ${run}. 원인을 확인한 뒤 다시 측정합니다." >&2
        return 1
    fi
    # 견적 발급 검산: 성공 응답(예열 포함) 수만큼 정확히 견적이 저장됐는지. 다르면 응답과 저장이 어긋난 것이라 멈춘다
    if [ "${SCRIPT:-rate-query.js}" = quote-issue.js ]; then
        local ok_all saved
        ok_all=$(value "$run" okAll)
        saved=$(nested "results/$1-vus$2-db.json" quoteRows all)
        if [ "$ok_all" != "$saved" ]; then
            echo "견적 검산 실패: 성공 응답 ${ok_all}건, 저장된 견적 ${saved}건 (${run})" >&2
            return 1
        fi
    fi
}

# 단계별 중앙값 표(마크다운)를 표준 출력으로: summary_table <LABEL> <STAGES>
# 결과 파일은 results/<LABEL>-r<회차>-vus<VUS>.json(k6)과 -db.json(DB 지표)이다.
# 단계마다 있는 회차를 모두 쓴다. 편차가 큰 조합에 추가로 잰 회차(r4·r5)는 그 조합에만 들어간다.
# TPS 편차 = (회차 중 최대 - 최소) / 중앙값. 10%를 넘으면 ⚠를 붙인다 (그 조합만 2회 추가 측정하는 기준, README 참고).
summary_table() {
    local label=$1 stages=$2 extra_column
    # 스크립트별 열: 환율 조회는 오래된 환율 응답 비율, 견적 발급은 저장된 견적 수 / 성공 응답 수 (예열 포함, 회차 합. 같아야 함)
    # 견적 발급은 측정이 끝난 시점 Redis 사용 메모리(회차 중 최대)도 붙인다. 견적이 Redis에 있으면 발급 수만큼 늘어난다
    if [ "${SCRIPT:-rate-query.js}" = quote-issue.js ]; then extra_column="견적 저장 / 성공 응답 (합), Redis MB (최대)"; else extra_column="오래된 응답 %%"; fi
    printf "| 동시 요청 | TPS | TPS(회차별) | TPS 편차 %% | P50 | P95 | P99 | 연결 대기 | 연결 얻는 ms | 연결 얻은 횟수 | 환율 SELECT (측정 구간) | DB 활성 세션 | 캐시 hit %% | ${extra_column} | 실패율 %% (최대) | 4xx / 5xx / 응답 없음 (합) | CPU 전체 %% | 앱 CPU %% |\n"
    printf "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n"
    local vus f
    for vus in $stages; do
        local k6=() db=()
        for f in results/"${label}"-r*-vus"${vus}".json; do
            [ -e "$f" ] || continue
            k6+=("$f")
            db+=("${f%.json}-db.json")
        done
        local tps tps_median spread extra cache_hit
        tps=$(for f in "${k6[@]}"; do value "$f" successTps; done)
        tps_median=$(echo "$tps" | median)
        spread=$(echo "$tps" | sort -n | awk -v m="$tps_median" '{ v[NR] = $1 } END { s = m ? (v[NR] - v[1]) / m * 100 : 0; printf "%.1f%s\n", s, (s > 10 ? " ⚠" : "") }')
        if [ "${SCRIPT:-rate-query.js}" = quote-issue.js ]; then
            local saved_sum
            saved_sum=$(for f in "${db[@]}"; do nested "$f" quoteRows all; done | awk '{ s += $1 } END { print s + 0 }')
            extra="${saved_sum} / $(sum okAll "${k6[@]}"), $(for f in "${db[@]}"; do nested "$f" redis usedMb; done | sort -n | tail -1)"
        else
            extra=$(for f in "${k6[@]}"; do
                awk -v s="$(value "$f" staleResponses)" -v r="$(value "$f" requests)" 'BEGIN { printf "%.2f\n", r ? s / r * 100 : 0 }'
            done | median)
        fi
        # 캐시 hit 비율 = hit / (hit + miss + error). 캐시를 쓰지 않는 jar면 "-"
        cache_hit=$(for f in "${db[@]}"; do
            h=$(nested "$f" cacheLookups hit)
            if [ -n "$h" ]; then
                awk -v h="$h" -v m="$(nested "$f" cacheLookups miss)" -v e="$(nested "$f" cacheLookups error)" \
                    'BEGIN { t = h + m + e; printf "%.2f\n", t ? h / t * 100 : 0 }'
            fi
        done | median)
        printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" "$vus" \
            "$tps_median" \
            "$(echo "$tps" | paste -sd/)" \
            "$spread" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p50; done | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p95; done | median)" \
            "$(for f in "${k6[@]}"; do nested "$f" latencyMs p99; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" hikariPending avg; done | median)" \
            "$(for f in "${db[@]}"; do value "$f" acquireMsAvg; done | median)" \
            "$(for f in "${db[@]}"; do value "$f" acquireCount; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" rateSelectCalls measure; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" dbActiveSessions avg; done | median)" \
            "$cache_hit" \
            "$extra" \
            "$(for f in "${k6[@]}"; do value "$f" failureRate; done | sort -n | tail -1)" \
            "$(sum '4xx' "${k6[@]}") / $(sum '5xx' "${k6[@]}") / $(sum noResponse "${k6[@]}")" \
            "$(for f in "${db[@]}"; do nested "$f" cpuPercent systemAvg; done | median)" \
            "$(for f in "${db[@]}"; do nested "$f" cpuPercent appAvg; done | median)"
    done
}
