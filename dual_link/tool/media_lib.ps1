# Helpers for driving the emulators to capture store screenshots and videos (dot-source this file).
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$A = 'emulator-5554'; $B = 'emulator-5556'; $pkg = 'com.duallink.app'
$raw = "D:\NON PTA MOBILE Rename\dual_link\play\assets\raw"
New-Item -ItemType Directory -Force $raw | Out-Null

function Sh($dev, [string]$cmd) { & $adb -s $dev shell $cmd 2>$null }

# All on-screen nodes with text/content-desc and their centre point.
function Nodes($dev) {
    Sh $dev 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1'
    $x = (& $adb -s $dev shell cat /sdcard/ui.xml 2>$null) -join ''
    foreach ($m in [regex]::Matches($x, '<node [^>]*?(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        [pscustomobject]@{ T = [Net.WebUtility]::HtmlDecode($m.Groups[1].Value)
            X = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2; Y = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2 }
    }
}

# Taps the first element whose text starts with $text (waits up to $sec seconds for it).
function TapText($dev, [string]$text, [int]$sec = 10) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $sec) {
        $n = Nodes $dev | Where-Object { $_.T -like "$text*" } | Select-Object -First 1
        if ($n) { Sh $dev "input tap $([int]$n.X) $([int]$n.Y)"; return $true }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "  [media] '$text' not found on $dev"; return $false
}

function Shot($dev, [string]$name) {
    Sh $dev 'screencap -p /sdcard/s.png'
    & $adb -s $dev pull /sdcard/s.png "$raw\$name.png" 2>$null | Out-Null
    Write-Host "  shot $name"
}

function DemoStatusBar($dev) {
    Sh $dev 'settings put global sysui_demo_allowed 1'
    foreach ($c in @('enter', 'clock -e hhmm 1200', 'battery -e level 100 -e plugged false', 'network -e wifi show -e level 4 -e mobile hide',
            'notifications -e visible false')) {
        $parts = $c -split ' ', 2
        Sh $dev "am broadcast -a com.android.systemui.demo -e command $($parts[0]) $(if ($parts.Count -gt 1) { $parts[1] })" | Out-Null
    }
}

function AddContact($dev, [string]$name, [string]$number) {
    Sh $dev "content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:local --bind account_name:s:local" | Out-Null
    $id = ((Sh $dev "content query --uri content://com.android.contacts/raw_contacts --projection _id") -join "`n" |
        Select-String -AllMatches '_id=(\d+)').Matches | ForEach-Object { [int]$_.Groups[1].Value } | Sort-Object | Select-Object -Last 1
    Sh $dev "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'$name'" | Out-Null
    Sh $dev "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$number --bind data2:i:2" | Out-Null
}
