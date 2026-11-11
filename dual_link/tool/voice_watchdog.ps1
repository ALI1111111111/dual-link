# Keeps Dual Link's voice helper running on Phone A from this PC: whenever Phone A is reachable
# over adb (USB or Wi-Fi) and the helper isn't running, it starts it again.
# Stop with Ctrl+C. Temporary until Dual Link can restart the helper by itself on Phone A.
#
# Usage: powershell -ExecutionPolicy Bypass -File tool\voice_watchdog.ps1 [-Model RMX3363]

param([string]$Model = 'RMX3363')

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
while ($true) {
    # Find Phone A by model (works for USB and wireless serials alike).
    $serial = & $adb devices -l 2>$null | Where-Object { $_ -match "\sdevice\s.*model:$Model" } |
        ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
    if ($serial) {
        $alive = & $adb -s $serial shell "pgrep -f com.duallink.app.voice.VoiceHelper" 2>$null
        if (-not $alive) {
            Write-Host "$(Get-Date -Format T) helper not running on $serial -> starting"
            & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\start_voice.ps1" -Device $serial 2>&1 | Select-Object -Last 1
        }
    }
    Start-Sleep 5
}
