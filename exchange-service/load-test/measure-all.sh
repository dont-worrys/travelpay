#!/usr/bin/env bash
# 동시 요청 50 / 100 / 300 단계를 단계마다 여러 번(기본 3번) 측정하고, 단계별 중앙값 표를 만듭니다.
# 한 번의 측정은 measure.sh가 합니다(측정마다 앱 재시작 → 이력 적재 → k6 → 지표 정리).
# 버전(jar) 여러 개를 같은 날 비교할 때는 순서를 교차하는 measure-compare.sh를 씁니다.
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
source ./measure-common.sh
summary="results/${LABEL}-summary.md"
# 앞 실행의 요약이 남아 이번 실행 결과로 오인되지 않게 먼저 지운다 (모든 회차가 성공해야 새로 생김)
rm -f "$summary"

for vus in $STAGES; do
    for rep in $(seq 1 "$REPS"); do
        echo "=== ${LABEL} 동시 요청 ${vus}개, ${rep}/${REPS}회"
        ./measure.sh "${LABEL}-r${rep}" "$vus" >/dev/null
        check_run "${LABEL}-r${rep}" "$vus"
    done
done

summary_table "$LABEL" "$REPS" "$STAGES" >"$summary"
cat "$summary"
