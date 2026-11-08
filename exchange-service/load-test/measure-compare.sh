#!/usr/bin/env bash
# 버전(jar) 여러 개를 같은 날 비교 측정합니다. 회차마다 부하 단계 순서와 버전 순서를 돌려,
# 노트북 발열처럼 시간이 지날수록 나빠지는 조건이 특정 버전·단계에 몰리지 않게 합니다.
# 한 번의 측정은 measure.sh가 합니다(측정마다 앱 재시작 → 이력 적재 → k6 → 지표 정리).
#
# 사용법: [SCRIPT=..] [REPS=3] [STAGES="50 100 300"] [START_AT=1] ./measure-compare.sh <PREFIX> <이름=jar 경로>...
#   예: ./measure-compare.sh rate v0=../../../travelpay-v0/exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar \
#                                 v05=../../../travelpay-v05/... v1=../../../travelpay-v1/...
#   결과: results/<PREFIX>-<이름>-r<회차>-vus<VUS>.json(-db.json), 버전별 results/<PREFIX>-<이름>-summary.md,
#         진행 기록 results/<PREFIX>-progress.log
#   START_AT=N: 중간에 멈췄을 때 N번째 측정부터 이어서 (진행 기록의 번호)
#
# 순서 (회차 r, 회차 안 단계 위치 p, 버전 n개):
#   단계 순서는 회차마다 한 칸씩 돌린다          예: 1회 50·100·300 / 2회 100·300·50 / 3회 300·50·100
#   버전 순서는 (2 × (r - 1) + p) mod n칸 돌린다  → 같은 단계 안에서 각 버전이 처음·중간·끝에 한 번씩 온다 (버전 3개 기준)
# 측정 하나라도 무효(실패 요청·불완전 응답)면 그 자리에서 멈춘다.
set -euo pipefail

PREFIX=${1:?PREFIX(결과 파일 이름 앞부분)를 주세요}
shift
[ $# -ge 2 ] || { echo "비교할 버전을 2개 이상 주세요 (이름=jar 경로)" >&2; exit 1; }
REPS=${REPS:-3}
STAGES=${STAGES:-"50 100 300"}
START_AT=${START_AT:-1}
export SCRIPT=${SCRIPT:-rate-query.js}

cd "$(dirname "$0")"
source ./measure-common.sh

names=() jars=()
for arg in "$@"; do
    names+=("${arg%%=*}")
    jars+=("${arg#*=}")
    [ -f "${arg#*=}" ] || { echo "jar가 없습니다: ${arg#*=}" >&2; exit 1; }
done
n=${#names[@]}
read -ra stage_list <<<"$STAGES"
s=${#stage_list[@]}
log="results/${PREFIX}-progress.log"
mkdir -p results

# 측정 순서표 만들기: "회차 단계 버전번호" 한 줄씩
schedule=()
for rep in $(seq 1 "$REPS"); do
    for p in $(seq 0 $((s - 1))); do
        vus=${stage_list[$(( (p + rep - 1) % s ))]}
        k=$(( (2 * (rep - 1) + p) % n ))
        for q in $(seq 0 $((n - 1))); do
            schedule+=("$rep $vus $(( (q + k) % n ))")
        done
    done
done
total=${#schedule[@]}

{
    echo "# $(date '+%F %T') 시작: PREFIX=${PREFIX}, REPS=${REPS}, STAGES=${STAGES}, 측정 ${total}회, START_AT=${START_AT}"
    for i in $(seq 0 $((n - 1))); do
        commit=$(git -C "$(dirname "${jars[$i]}")" rev-parse --short HEAD 2>/dev/null || echo "?")
        echo "#   ${names[$i]} = ${jars[$i]} (커밋 ${commit})"
    done
} | tee -a "$log"

idx=0
for entry in "${schedule[@]}"; do
    idx=$((idx + 1))
    [ "$idx" -lt "$START_AT" ] && continue
    read -r rep vus v <<<"$entry"
    name=${names[$v]}
    label="${PREFIX}-${name}-r${rep}"
    echo "=== [${idx}/${total}] $(date '+%T') ${name} 동시 요청 ${vus}개, ${rep}회차" | tee -a "$log"
    APP_JAR=${jars[$v]} ./measure.sh "$label" "$vus" >/dev/null
    check_run "$label" "$vus"
    echo "    TPS $(value "results/${label}-vus${vus}.json" successTps), P95 $(nested "results/${label}-vus${vus}.json" latencyMs p95)ms" | tee -a "$log"
done

echo "# $(date '+%F %T') 측정 끝" | tee -a "$log"
for name in "${names[@]}"; do
    summary="results/${PREFIX}-${name}-summary.md"
    summary_table "${PREFIX}-${name}" "$REPS" "$STAGES" >"$summary"
    echo
    echo "## ${name}"
    cat "$summary"
done
