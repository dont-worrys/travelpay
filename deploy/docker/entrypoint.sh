#!/bin/bash
# ECS Task의 시작 스크립트입니다. 실행 이미지(Ubuntu 26.04)의 env는 다른 이름으로 불리면 실행을 거부하므로
# 인터프리터를 직접 지정합니다.
# Spring은 종료할 때 웹 서버를 먼저 닫고 Eureka 등록 해제를 맨 나중에 합니다. 그 사이 다른 서비스는 캐시된 주소로
# 요청을 보내므로, Eureka에 등록하는 서비스는 종료 신호를 받으면 먼저 OUT_OF_SERVICE로 바꾸고 캐시가 비워질 때까지
# 기다린 뒤 Java에 종료 신호를 보냅니다. 대기 시간과 Spring 종료 시간의 합은 ECS stopTimeout보다 짧아야 합니다.
set -euo pipefail

# 인자를 주면 앱 대신 그 명령을 실행합니다(시험 실행용).
if [[ $# -gt 0 ]]; then
  exec "$@"
fi

app=(java -jar /opt/travelpay/app.jar)
if [[ -z "${TRAVELPAY_EUREKA_URL:-}" ]]; then
  exec "${app[@]}"
fi

: "${TRAVELPAY_EUREKA_APP:?}" "${TRAVELPAY_SERVICE_PORT:?}" "${ECS_CONTAINER_METADATA_URI_V4:?}"

# awsvpc Task에는 메타데이터용 링크 로컬 인터페이스도 있어 자동 선택 대신 ECS가 알려 준 Task IP를 씁니다.
metadata="$(curl --fail --silent --show-error --max-time 5 "${ECS_CONTAINER_METADATA_URI_V4}")"
task_ip="$(grep -oE '"IPv4Addresses"[[:space:]]*:[[:space:]]*\[[[:space:]]*"[0-9.]+"' <<<"${metadata}" \
  | head -n1 | grep -oE '[0-9]+(\.[0-9]+){3}' || true)"
if [[ ! "${task_ip}" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
  echo "ECS 메타데이터에서 Task IP를 찾지 못했습니다." >&2
  exit 1
fi
export EUREKA_INSTANCE_IP_ADDRESS="${task_ip}"
export EUREKA_INSTANCE_INSTANCEID="${task_ip}:${TRAVELPAY_SERVICE_PORT}"

"${app[@]}" &
java_pid=$!

drain() {
  trap - TERM INT
  if ! curl --fail --silent --show-error --max-time 5 -X PUT \
    "${TRAVELPAY_EUREKA_URL%/}/apps/${TRAVELPAY_EUREKA_APP}/${EUREKA_INSTANCE_INSTANCEID}/status?value=OUT_OF_SERVICE"; then
    echo "Eureka 상태를 바꾸지 못했습니다. 대기 후 종료를 계속합니다." >&2
  fi
  sleep "${TRAVELPAY_DRAIN_SECONDS:-20}"
  kill -TERM "${java_pid}" 2>/dev/null || true
}
trap drain TERM INT

# 신호를 받으면 wait가 먼저 끝나므로 Java가 실제로 종료될 때까지 다시 기다려 종료 코드를 그대로 돌려줍니다.
set +e
wait "${java_pid}"
status=$?
while kill -0 "${java_pid}" 2>/dev/null; do
  wait "${java_pid}"
  status=$?
done
exit "${status}"
