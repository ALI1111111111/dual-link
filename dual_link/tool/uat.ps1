# Dual Link ??? end-to-end UAT on two emulators.
#   Phone A (gateway, has "SIM") = emulator-5554     Phone B (client) = emulator-5556
# Phone B reaches Phone A through the PC: B -> 10.0.2.2:47820 (host) -> adb forward -> A:47820.
# Incoming SMS are injected with `adb emu sms send`; SMS that A sends to "5556" land on emulator B's SIM.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tool\uat.ps1 [-Apk path] [-SkipInstall]

param(
    [string]$Apk = "$PSScriptRoot\..\build\app\outputs\flutter-apk\app-debug.apk",
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Continue'  # adb writes harmless warnings to stderr
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$A = 'emulator-5554'; $B = 'emulator-5556'
$pkg = 'com.duallink.app'
$port = 47820
$results = New-Object System.Collections.Generic.List[object]

# No named params here: PowerShell would treat adb flags like -d as short for a param name.
function Adb { $dev, $rest = $args; $argv = @('-s', $dev) + @($rest); & $adb $argv 2>$null }
function Hook($dev, [string]$extras) { Adb $dev shell "am broadcast -n $pkg/.TestHooks $extras" | Out-Null }
function Log($dev) { (Adb $dev logcat -d -v time -s DualLinkClient:I DualLinkGateway:I DualLinkTest:I) -join "`n" }
function Count($dev, [string]$pattern) { ([regex]::Matches((Log $dev), $pattern)).Count }

# Waits until $pattern appears at least $n times in the device log. Returns seconds taken, or -1.
function WaitFor($dev, [string]$pattern, [int]$n = 1, [int]$timeoutSec = 20) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        if ((Count $dev $pattern) -ge $n) { return [math]::Round($sw.Elapsed.TotalSeconds, 2) }
        Start-Sleep -Milliseconds 200
    }
    return -1
}

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    $results.Add([pscustomobject]@{ Test = $name; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Detail = $detail })
    Write-Host ("[{0}] {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail)
}

function SmsIn([string]$from, [string]$text) { Adb $A emu sms send $from $text | Out-Null }

# ---------------------------------------------------------------- setup
Write-Host "== Setup"
foreach ($d in $A, $B) {
    if ((Adb $d get-state) -ne 'device') { throw "$d is not running" }
    if (-not $SkipInstall) {
        Adb $d uninstall $pkg | Out-Null
        Adb $d install -r -g $Apk | Out-Null
    } else {
        Adb $d shell am force-stop $pkg | Out-Null
        Adb $d shell pm clear $pkg | Out-Null   # fresh inbox/queues every run
        Adb $d shell pm grant $pkg android.permission.POST_NOTIFICATIONS | Out-Null
    }
    Adb $d shell dumpsys deviceidle whitelist +$pkg | Out-Null   # same as user tapping "allow background"
    Adb $d logcat -G 8M | Out-Null   # keep the whole run in the log buffer
    Adb $d logcat -c
}
Adb $A shell pm grant $pkg android.permission.RECEIVE_SMS
Adb $A shell pm grant $pkg android.permission.SEND_SMS
Adb $A shell pm grant $pkg android.permission.READ_SMS
Adb $A shell pm grant $pkg android.permission.READ_PHONE_STATE
Adb $B shell pm grant $pkg android.permission.POST_NOTIFICATIONS
& $adb -s $A forward "tcp:$port" "tcp:$port" | Out-Null

Hook $A "--es cmd gateway --es host 10.0.2.2"
if ((WaitFor $A 'UAT qr=') -lt 0) { throw 'gateway did not start' }
$qr = ([regex]::Match((Log $A), 'UAT qr=(\{.*\})')).Groups[1].Value | ConvertFrom-Json
Hook $B "--es cmd pair --es h $($qr.h) --ei p $($qr.p) --es k $($qr.k)"

# ---------------------------------------------------------------- T1 connect
$t = WaitFor $B 'UAT connected' 1 30
Check 'T1 Phone B connects to Phone A' ($t -ge 0) "in ${t}s"

# ---------------------------------------------------------------- T2 incoming SMS in real time
# Emulator's fake modem sometimes holds SMS for 20-30 s, so allow time here and judge the app's own
# latency separately (see "App latency" at the end, measured from log timestamps per message id).
SmsIn '03001234567' 'UAT hello from a friend'
$t = WaitFor $B 'UAT sms_received id=\S+ new=true' 1 45
Check 'T2 Incoming SMS reaches Phone B' ($t -ge 0) "end-to-end ${t}s incl. emulator modem"
$t = WaitFor $A 'UAT sms_acked' 1 5
Check 'T2b Phone B confirms, Phone A clears its copy' ($t -ge 0)

# ---------------------------------------------------------------- T3 send SMS from Phone B through Phone A's SIM
Hook $B "--es cmd send --es to 5556 --es body 'UAT reply from phone B'"
$t = WaitFor $B 'UAT send_status id=\S+ state=sent' 1 45
Check 'T3 Reply sent through SIM, "sent" shown on Phone B' ($t -ge 0) "round trip ${t}s"

# ---------------------------------------------------------------- T4 long (multi-part) messages both ways
$long = ('Long message test ' * 12).Trim()   # ~215 chars = 2 SMS parts
SmsIn '03001234567' $long
$t1 = WaitFor $B 'UAT sms_received id=\S+ new=true' 2 45
Hook $B "--es cmd send --es to 5556 --es body '$long'"
$t2 = WaitFor $A 'UAT send_start id=\S+ parts=2' 1 10
$t3 = WaitFor $B 'UAT send_status id=\S+ state=sent' 2 45
Check 'T4 Long SMS (2 parts) received and sent' ($t1 -ge 0 -and $t2 -ge 0 -and $t3 -ge 0)

# ---------------------------------------------------------------- T5 connection drop: nothing lost, nothing duplicated
& $adb -s $A forward --remove "tcp:$port" | Out-Null
Hook $A '--es cmd stopgw'                                  # also kills the live connection
$t = WaitFor $B 'UAT disconnected' 1 15
Check 'T5a Phone B notices the drop' ($t -ge 0) "in ${t}s"
Hook $A '--es cmd startgw'
Start-Sleep 2
SmsIn '03009999999' 'UAT while offline 1'
SmsIn '03009999999' 'UAT while offline 2'
Hook $B "--es cmd send --es to 5556 --es body 'UAT queued while offline'"
Start-Sleep 3
$inBefore = Count $B 'UAT sms_received id=\S+ new=true'
Check 'T5b Nothing delivered while link is down' ($inBefore -eq 2)
& $adb -s $A forward "tcp:$port" "tcp:$port" | Out-Null
$t = WaitFor $B 'UAT sms_received id=\S+ new=true' 4 75
Check 'T5c Offline SMS delivered after reconnect' ($t -ge 0) "after ${t}s (back-off <= 60s)"
$t = WaitFor $B 'UAT send_status id=\S+ state=sent' 3 30
Check 'T5d Queued reply sent after reconnect' ($t -ge 0)

# ---------------------------------------------------------------- T6 Wi-Fi off/on on Phone B (battery: no retries while off)
Adb $B shell svc wifi disable | Out-Null
$t = WaitFor $B 'UAT wifi_down' 1 15
$connBefore = Count $B 'connection to .* failed'
Start-Sleep 20
$retriesWhileOff = (Count $B 'connection to .* failed') - $connBefore
Check 'T6a No reconnect attempts while off Wi-Fi (saves battery)' ($t -ge 0 -and $retriesWhileOff -le 1) "attempts while off: $retriesWhileOff"
$c0 = Count $B 'UAT connected'
Adb $B shell svc wifi enable | Out-Null
$t = WaitFor $B 'UAT connected' ($c0 + 1) 30
Check 'T6b Reconnects as soon as Wi-Fi returns' ($t -ge 0) "in ${t}s"
SmsIn '03001234567' 'UAT after wifi back'
$t = WaitFor $B 'UAT sms_received id=\S+ new=true' 5 45
Check 'T6c Real-time again after Wi-Fi returns' ($t -ge 0) "latency ${t}s"

# ---------------------------------------------------------------- T7 duplicates check
Hook $B '--es cmd count'; Hook $A '--es cmd count'
Start-Sleep 1
$cb = [regex]::Match((Log $B), 'UAT count in=(\d+) sent=(\d+)').Groups
$ca = [regex]::Match((Log $A), 'UAT count in=\d+ sent=\d+ pending=(\d+) gw_sent=(\d+)').Groups
$dupSends = Count $A 'UAT send_start'
Check 'T7a Phone B inbox has exactly 5 SMS (no duplicates)' ($cb[1].Value -eq '5') "inbox=$($cb[1].Value)"
$dup = [regex]::Match((Log $B), 'UAT count .* dup=(\d+)').Groups[1].Value
Check 'T7d History import created no duplicate messages' ($dup -eq '0') "duplicates=$dup"
Check 'T7b Exactly 3 replies sent, each once' ($cb[2].Value -eq '3' -and $dupSends -eq 3) "sent=$($cb[2].Value) sim_sends=$dupSends"
Check 'T7c Phone A has nothing left waiting' ($ca[1].Value -eq '0') "pending=$($ca[1].Value)"

# ---------------------------------------------------------------- T8 security: old key rejected after "new pairing code"
$c0 = Count $B 'UAT connected'
Hook $A '--es cmd rotate'
Start-Sleep 8
$rejected = Count $A 'rejected connection'
Check 'T8a Old paired phone is locked out after key change' ($rejected -ge 1 -and (Count $B 'UAT connected') -eq $c0) "rejected attempts: $rejected"
Hook $A "--es cmd gateway --es host 10.0.2.2"
$null = WaitFor $A 'UAT qr=' 2
$qr = ([regex]::Matches((Log $A), 'UAT qr=(\{.*\})') | Select-Object -Last 1).Groups[1].Value | ConvertFrom-Json
Hook $B "--es cmd pair --es h $($qr.h) --ei p $($qr.p) --es k $($qr.k)"
$t = WaitFor $B 'UAT connected' ($c0 + 1) 30
Check 'T8b Re-pairing with the new code works' ($t -ge 0)

# ---------------------------------------------------------------- T9 security: stranger with no key
$rej0 = Count $A 'rejected connection'
$probe = New-Object Net.Sockets.TcpClient('127.0.0.1', $port)
$w = New-Object IO.StreamWriter($probe.GetStream()); $w.Write("aGVsbG8gZ2F0ZXdheQ==`n"); $w.Flush()
Start-Sleep 2
$probe.Close()
Check 'T9 Stranger without the key is refused' ((Count $A 'rejected connection') -gt $rej0)

# ---------------------------------------------------------------- T10 security: invalid number and rate limit
Hook $B "--es cmd send --es to '*123#' --es body 'UAT ussd'"
$t = WaitFor $A 'UAT send_result id=\S+ state=failed reason=invalid_number' 1 10
Check 'T10a USSD / invalid numbers refused' ($t -ge 0)
1..22 | ForEach-Object { Hook $B "--es cmd send --es to 5556 --es body 'UAT burst $_'" }
$t = WaitFor $A 'reason=rate_limited' 1 40
Check 'T10b Burst of 22 SMS: rate limit kicks in (max 20/min)' ($t -ge 0)

# ---------------------------------------------------------------- T14/T15 history + SIM choice
# History is requested on every reconnect; T7 already proved it caused no duplicate messages.
Check 'T14 SMS history from Phone A delivered (and de-duplicated, see T7)' ((Count $B 'UAT history_done n=[1-9]') -ge 1) "history syncs: $(Count $B 'UAT history_done')"

# ---------------------------------------------------------------- app latency (excludes the emulator modem)
# Both emulators use the PC clock, so log timestamps can be compared directly.
function Stamps($dev, [string]$pattern) {
    $h = @{}
    foreach ($m in [regex]::Matches((Log $dev), "(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}).*$pattern")) {
        $id = $m.Groups[2].Value
        if (-not $h.ContainsKey($id)) { $h[$id] = [datetime]::ParseExact($m.Groups[1].Value, 'MM-dd HH:mm:ss.fff', $null) }
    }
    $h
}
# Phone B connection timeline. A message created while B was offline is timed from the reconnect.
$conn = [regex]::Matches((Log $B), '(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}).*UAT (connected|disconnected)') | ForEach-Object {
    [pscustomobject]@{ T = [datetime]::ParseExact($_.Groups[1].Value, 'MM-dd HH:mm:ss.fff', $null); Up = $_.Groups[2].Value -eq 'connected' } }
function Ready([datetime]$t) {
    $last = $conn | Where-Object { $_.T -le $t } | Select-Object -Last 1
    if ($last -and $last.Up) { return $t }
    $next = $conn | Where-Object { $_.T -gt $t -and $_.Up } | Select-Object -First 1
    if ($next) { $next.T } else { $t }
}
function Latencies($from, $to) {
    @($to.Keys | Where-Object { $from.ContainsKey($_) } | ForEach-Object { ($to[$_] - (Ready $from[$_])).TotalMilliseconds })
}
$lat = Latencies (Stamps $A 'UAT sms_in id=(\S+)') (Stamps $B 'UAT sms_received id=(\S+) new=true')
$max = if ($lat.Count) { ($lat | Measure-Object -Maximum).Maximum } else { -1 }
Check 'T11 App latency Phone A -> Phone B (incoming SMS)' ($lat.Count -ge 1 -and $max -lt 1000) "$($lat.Count) msgs, max ${max} ms (target < 1000 ms)"
# Normal replies (the T10 burst is judged separately: the SIM sends one at a time, so a burst queues up).
$queued = Stamps $B 'UAT send_queued id=(\S+)'
$burstStart = ([regex]::Matches((Log $B), '(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}).*UAT send_queued') | Select-Object -Last 22 | Select-Object -First 1).Groups[1].Value
$burstT = [datetime]::ParseExact($burstStart, 'MM-dd HH:mm:ss.fff', $null)
$normal = @{}; $burst = @{}
foreach ($k in $queued.Keys) { if ($queued[$k] -lt $burstT) { $normal[$k] = $queued[$k] } else { $burst[$k] = $queued[$k] } }
$started = Stamps $A 'UAT send_start id=(\S+)'
$lat2 = Latencies $normal $started
$max2 = if ($lat2.Count) { ($lat2 | Measure-Object -Maximum).Maximum } else { -1 }
Check 'T12 App latency Phone B -> Phone A (reply handed to SIM)' ($lat2.Count -ge 1 -and $max2 -lt 1000) "$($lat2.Count) msgs, max ${max2} ms (target < 1000 ms)"
$lat3 = Latencies $burst $started
$max3 = if ($lat3.Count) { ($lat3 | Measure-Object -Maximum).Maximum } else { -1 }
$limited = Count $A 'reason=rate_limited'
Check 'T13 Burst of 22: each SMS sent within 3 s or refused by the limit' (($lat3.Count + $limited) -eq 22 -and $lat3.Count -ge 17 -and $max3 -lt 3000) "$($lat3.Count) sent (slowest ${max3} ms), $limited refused"

# ---------------------------------------------------------------- T15 SIM choice (runs last so it doesn't skew T12/T13)
Hook $B "--es cmd send --es to 5556 --ei sim 999 --es body 'UAT wrong sim'"
$t = WaitFor $A 'reason=sim_not_available' 1 10
Check 'T15 Sending on a SIM that does not exist is refused' ($t -ge 0)
$t = WaitFor $B 'UAT send_status id=\S+ state=failed reason=sim_not_available' 1 10
Check 'T15b Phone B is told why it failed' ($t -ge 0)

# ---------------------------------------------------------------- summary
Write-Host ""
$results | Format-Table -AutoSize
$failed = @($results | Where-Object Result -eq 'FAIL').Count
Write-Host "$($results.Count - $failed) passed, $failed failed"
exit $failed
