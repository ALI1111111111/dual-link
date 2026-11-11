# Starts Dual Link's voice helper on Phone A (run again after each Phone A reboot).
# No root: the helper is Dual Link's own code, run as the ADB shell user, which may use Android's
# call-audio interception API. A fresh random key is created each time and given to both the
# helper and the app; the app only accepts the key through an ADB-only channel.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tool\start_voice.ps1 [-Device <adb serial>]

param([string]$Device = 'adb-58a4947e-YEpKdB._adb-tls-connect._tcp')

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.duallink.app'
function Sh([string]$cmd) { & $adb -s $Device shell $cmd 2>$null }

if ((& $adb -s $Device get-state 2>$null) -ne 'device') { throw "Phone A ($Device) is not connected over adb" }
$apk = ((Sh "pm path $pkg") -replace 'package:', '').Trim()
if (-not $apk) { throw 'Dual Link is not installed on Phone A' }

# 256-bit random key (base64; no characters that need shell quoting except + / =)
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$key = [Convert]::ToBase64String($bytes)

Sh "pkill -f com.duallink.app.voice.VoiceHelper" | Out-Null
Start-Sleep -Milliseconds 500
# Helper first (it waits for the app), detached so it survives adb disconnecting.
Sh "CLASSPATH=$apk setsid nohup app_process / com.duallink.app.voice.VoiceHelper '$key' > /data/local/tmp/duallink_voice.log 2>&1 < /dev/null &" | Out-Null
Start-Sleep 2
Sh "am broadcast -n $pkg/.VoiceKeyReceiver --es key '$key'" | Out-Null
Start-Sleep 3

$running = Sh "pgrep -f com.duallink.app.voice.VoiceHelper"
$log = Sh "cat /data/local/tmp/duallink_voice.log"
Write-Host "helper pid: $running"
Write-Host $log
if ($running -and ($log -match 'app connected')) { Write-Host "Voice helper is running and connected to Dual Link." }
elseif ($running) { Write-Host "Helper running, waiting for Dual Link to connect (is Phone A's gateway on?)." }
else { Write-Host "Helper did not start; see log above."; exit 1 }
