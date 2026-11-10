#!/usr/bin/env bash
# 측정하는 PC의 상태를 JSON 한 줄로 출력합니다(#250). measure.sh가 k6 직전·직후에 불러 결과 파일에 남깁니다.
# 같은 날 비교할 측정끼리 조건이 같았는지, 측정 중에 바뀌지 않았는지 나중에 확인하려는 것입니다.
# (2026-11-10: 11/9 측정의 전원 모드를 기록하지 않아, 다른 날 결과와 비교할 때 조건을 맞출 수 없었음)
#
#   powerMode         충전기 연결 시 Windows 전원 모드: best-performance / balanced / best-efficiency
#   charger           on(충전기 연결) / battery
#   teamServicePorts  PC에서 직접 띄운 팀 서비스 포트(8080~8085, 8090, 8761) 중 열린 수. 측정 중에는 0이어야 함
#   intellij          IntelliJ 실행 여부
# Windows가 아니면(PowerShell 없음) 값을 null로 둡니다.
set -uo pipefail

if ! command -v powershell.exe >/dev/null 2>&1; then
    echo '{"os": "other", "powerMode": null, "charger": null, "teamServicePorts": null, "intellij": null}'
    exit 0
fi

# 한글이 깨지지 않게 영문 값만 출력한다
powershell.exe -NoProfile -Command '
$k = Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes" -ErrorAction SilentlyContinue
$mode = switch ($k.ActiveOverlayAcPowerScheme) {
    "ded574b5-45a0-4f42-8737-46345c09c238" { "best-performance" }
    "961cc777-2547-4f9d-8174-7d86181b8a7a" { "best-efficiency" }
    "00000000-0000-0000-0000-000000000000" { "balanced" }
    default { "unknown" }
}
# 배터리 상태 코드는 충전 중·완충 등 여러 값이 "연결"이라, Windows의 AC 전원 연결 여부를 직접 읽는다
Add-Type -AssemblyName System.Windows.Forms
$line = [System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus
$charger = switch ($line) { "Online" { "on" } "Offline" { "battery" } default { "unknown" } }
$ports = @(8080, 8081, 8082, 8083, 8084, 8085, 8090, 8761)
$svc = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort } | Select-Object -ExpandProperty LocalPort -Unique).Count
$idea = if (Get-Process idea64 -ErrorAction SilentlyContinue) { "true" } else { "false" }
"{""os"": ""windows"", ""powerMode"": ""$mode"", ""charger"": ""$charger"", ""teamServicePorts"": $svc, ""intellij"": $idea}"
' | tr -d '\r'
