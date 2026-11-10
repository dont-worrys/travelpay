#!/usr/bin/env bash
# 버전(jar) 여러 개를 같은 날 비교 측정합니다. 회차마다 부하 단계 순서와 버전 순서를 돌려,
# 노트북 발열처럼 시간이 지날수록 나빠지는 조건이 특정 버전·단계에 몰리지 않게 합니다.
# 한 번의 측정은 measure.sh가 합니다(측정마다 앱 재시작 → 이력 적재 → k6 → 지표 정리).
#
# 사용법: [SCRIPT=..] [REPS=3] [STAGES="50 100 300"] [START_AT=1] [SUMMARY_ONLY=1] ./measure-compare.sh <PREFIX> <이름=jar 경로>...
#   예: ./measure-compare.sh rate v0=../../../travelpay-v0/exchange-service/build/libs/exchange-service-0.0.1-SNAPSHOT.jar \
#                                 v05=../../../travelpay-v05/... v1=../../../travelpay-v1/...
#   결과: results/<PREFIX>-<이름>-r<회차>-vus<VUS>.json(-db.json), 버전별 results/<PREFIX>-<이름>-summary.md,
#         진행 기록 results/<PREFIX>-progress.log
#   START_AT=N:     중간에 멈췄을 때 N번째 측정부터 이어서 (진행 기록의 번호)
#   SUMMARY_ONLY=1: 측정하지 않고 결과 검사와 요약만 다시 (편차가 큰 조합에 추가 측정을 더한 뒤 쓴다, README 참고)
#
# 순서 (회차 r, 회차 안 단계 위치 p, 버전 n개):
#   단계 순서는 회차마다 한 칸씩 돌린다          예: 1회 50·100·300 / 2회 100·300·50 / 3회 300·50·100
#   버전 순서는 (2 × (r - 1) + p) mod n칸 돌린다  → 같은 단계 안에서 각 버전이 처음·중간·끝에 한 번씩 온다 (버전 3개 기준)
# 측정 하나라도 무효(실패 요청·불완전 응답)면 그 자리에서 멈춘다.
# 요약을 만들기 전에 순서표의 모든 결과가 있고 유효한지, 추가 측정 결과까지 다시 검사한다.
# START_AT으로 이어서 측정하면 앞 회차는 이번 실행에서 검사하지 않았으므로, 빠지거나 무효인 결과가 요약에 섞이지 않게 하려는 것이다.
set -euo pipefail

PREFIX=${1:?PREFIX(결과 파일 이름 앞부분)를 주세요}
shift
[ $# -ge 2 ] || { echo "비교할 버전을 2개 이상 주세요 (이름=jar 경로)" >&2; exit 1; }
REPS=${REPS:-3}
STAGES=${STAGES:-"50 100 300"}
START_AT=${START_AT:-1}
SUMMARY_ONLY=${SUMMARY_ONLY:-0}
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

if [ "$SUMMARY_ONLY" != 1 ]; then
    # 처음부터 잴 때 같은 PREFIX 결과가 남아 있으면 멈춘다. 요약은 그 PREFIX의 모든 회차 파일을 읽으므로
    # 다른 날 결과가 섞이거나 1시간 넘게 잰 결과를 덮어쓰지 않게 한다. 다른 PREFIX를 쓰거나 옮긴 뒤 다시 실행한다
    if [ "$START_AT" = 1 ] && compgen -G "results/${PREFIX}-*-r*-vus*.json" >/dev/null; then
        echo "results/에 ${PREFIX} 결과가 이미 있습니다. 다른 PREFIX를 쓰거나 기존 결과를 옮긴 뒤 실행합니다 (이어서 잴 때는 START_AT)." >&2
        exit 1
    fi
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
fi

# 요약 전에 검사: 순서표의 모든 결과가 있어야 하고, 추가 측정(r4·r5 등)을 포함한 모든 결과가 유효해야 한다
for entry in "${schedule[@]}"; do
    read -r rep vus v <<<"$entry"
    label="${PREFIX}-${names[$v]}-r${rep}"
    if [ ! -f "results/${label}-vus${vus}.json" ] || [ ! -f "results/${label}-vus${vus}-db.json" ]; then
        echo "결과가 없습니다: ${label} 동시 요청 ${vus}개. 요약을 만들지 않습니다 (START_AT으로 빠진 측정부터 다시)." >&2
        exit 1
    fi
done
for name in "${names[@]}"; do
    for f in results/"${PREFIX}-${name}"-r*-vus*.json; do
        case "$f" in *-db.json) continue ;; esac
        base=$(basename "$f" .json)
        [ -f "results/${base}-db.json" ] || { echo "DB 지표가 없습니다: results/${base}-db.json" >&2; exit 1; }
        check_run "${base%-vus*}" "${base##*-vus}"
    done
done

for name in "${names[@]}"; do
    summary="results/${PREFIX}-${name}-summary.md"
    summary_table "${PREFIX}-${name}" "$STAGES" >"$summary"
    echo
    echo "## ${name}"
    cat "$summary"
done
