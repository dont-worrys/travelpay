#!/usr/bin/env bash
# 원장 지연 시나리오를 회차마다 순서를 돌려 가며 측정합니다(#282). 개선 전·후 jar에 같은 조건으로 씁니다.
# 같은 PC에서 연달아 재면 시간이 지날수록 조건이 바뀌므로(발열·백그라운드), 회차마다 시나리오 순서를 한 칸씩 돌려
# 특정 시나리오가 늘 처음이나 끝에 오지 않게 합니다(measure-compare.sh와 같은 생각).
#
# 사용법: [SCENARIOS="normal:0 slow:3000 timeout:6000"] [REPS=3] [START_AT=1] APP_JAR=<jar> ./measure-ledger-compare.sh <PREFIX>
#   예: APP_JAR=../../../travelpay-ledger-pre282/$J ./measure-ledger-compare.sh ledger-pre
#   결과: results/<PREFIX>-<시나리오>-r<회차>.json(k6) + -server.json(서버 지표), 진행 기록 results/<PREFIX>-progress.log
#   중간에 멈추면 진행 기록의 번호부터 START_AT으로 이어서 잽니다.
set -euo pipefail

PREFIX=${1:?PREFIX를 주세요 (예: ledger-pre)}
SCENARIOS=${SCENARIOS:-"normal:0 slow:3000 timeout:6000"}
REPS=${REPS:-3}
START_AT=${START_AT:-1}

cd "$(dirname "$0")"
: "${APP_JAR:?APP_JAR(측정할 jar 경로)를 주세요}"
read -ra scenario_list <<<"$SCENARIOS"
count=${#scenario_list[@]}
total=$((count * REPS))
progress="results/${PREFIX}-progress.log"
mkdir -p results
{
    echo "# $(date '+%F %T') 시작: PREFIX=${PREFIX}, SCENARIOS=${SCENARIOS}, REPS=${REPS}, 측정 ${total}회, START_AT=${START_AT}"
    echo "#   APP_JAR=${APP_JAR}"
} | tee -a "$progress"

n=0
for rep in $(seq 1 "$REPS"); do
    for i in $(seq 0 $((count - 1))); do
        n=$((n + 1))
        # 회차마다 시작 시나리오를 한 칸씩 민다 (1회차 normal·slow·timeout, 2회차 slow·timeout·normal …)
        entry=${scenario_list[$(((i + rep - 1) % count))]}
        name=${entry%%:*}
        delay=${entry##*:}
        if [ "$n" -lt "$START_AT" ]; then
            continue
        fi
        label="${PREFIX}-${name}-r${rep}"
        echo "=== [${n}/${total}] $(date '+%T') ${name} (원장 지연 ${delay}ms), ${rep}회차" | tee -a "$progress"
        if ! ./measure-ledger.sh "$label" "$delay" >"results/${label}.log" 2>&1; then
            echo "    실패: results/${label}.log 마지막 줄 → $(tail -1 "results/${label}.log")" | tee -a "$progress"
            exit 1
        fi
        # 한 줄 요약: 신청 p50·p95, 상태, 환율 조회 p95·p99, 요청 스레드
        orders=$(tr -d '\n ' <"results/${label}.json" | grep -o '"orders":{"requests":[0-9]*,"latencyMs":{[^}]*}' | grep -o '"p50":[0-9.]*\|"p95":[0-9.]*' | tr '\n' ' ')
        rates=$(tr -d '\n ' <"results/${label}.json" | grep -o '"rates":{"latencyMs":{[^}]*}' | grep -o '"p95":[0-9.]*\|"p99":[0-9.]*' | tr '\n' ' ')
        threads=$(tr -d '\n ' <"results/${label}-server.json" | grep -o '"tomcatThreads":{[^}]*}')
        echo "    신청 ${orders}/ 환율 조회 ${rates}/ ${threads}" | tee -a "$progress"
    done
done
echo "# $(date '+%F %T') 측정 끝" | tee -a "$progress"
