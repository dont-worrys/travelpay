#!/usr/bin/env bash
# 두 시각(유닉스 초) 사이에 Windows가 대기 모드(Modern Standby)에 들어간 횟수를 출력합니다(#250). Windows가 아니면 null.
# 사용법: bash standby-events.sh <시작 epoch> <끝 epoch>
#
# 측정 중 PC가 대기 모드에 들어가면 CPU가 제한되다가 Docker까지 멈춘다. 깊게 잠들면 표본 누락으로 무효가 되지만,
# 얕은 대기에서는 숫자가 나오고 검사를 통과해 느려진 결과가 섞일 수 있다(2026-11-10 21:10, 화면 끄기 5분 설정).
# 그래서 measure.sh가 k6 구간의 대기 모드 진입 기록(Kernel-Power 506)을 세어, 하나라도 있으면 무효로 끝낸다.
set -uo pipefail
FROM=${1:?시작 epoch를 주세요}
TO=${2:?끝 epoch를 주세요}

if ! command -v powershell.exe >/dev/null 2>&1; then
    echo null
    exit 0
fi

powershell.exe -NoProfile -Command "
\$from = [DateTimeOffset]::FromUnixTimeSeconds(${FROM}).LocalDateTime
\$to = [DateTimeOffset]::FromUnixTimeSeconds(${TO}).LocalDateTime
try {
    \$events = Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Kernel-Power'; Id = 506; StartTime = \$from; EndTime = \$to } -ErrorAction Stop
    @(\$events).Count
} catch {
    # 조건에 맞는 이벤트가 없으면 Get-WinEvent는 NoMatchingEventsFound 오류를 낸다. 이것만 0이고,
    # 로그를 읽지 못한 다른 오류(권한 등)는 error로 알려 measure.sh가 대기 모드를 확인하지 못한 측정을 무효로 한다
    if (\$_.FullyQualifiedErrorId -like 'NoMatchingEventsFound*') { 0 } else { 'error' }
}
" | tr -d '\r'
