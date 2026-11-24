param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet(0, 50, 100)][int]$CapKiB = 0,
    [string]$Screenshots
)
$ErrorActionPreference = 'Stop'
$sdk = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$fixture = Join-Path $PSScriptRoot '../APKs/Test files/messy-photo.png'
$name = 'MORPH-picker-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.png'
$remote = "/sdcard/Download/$name"

function Device {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
}
function Screen {
    foreach ($attempt in 1..10) {
        $dump = Device shell uiautomator dump /data/local/tmp/morph-picker-test.xml
        if ($dump -match 'dumped to') {
            return [xml]((Device shell cat /data/local/tmp/morph-picker-test.xml) -join '')
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Android did not expose an accessibility window.'
}
function Tap($node) {
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Missing control bounds' }
    Device shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2))
}
function Find-Control($xpath, [switch]$Scroll) {
    $deadline = (Get-Date).AddSeconds(50)
    do {
        $doc = Screen
        $systemWait = $doc.SelectSingleNode('//node[starts-with(@text,"System UI isn")]/../..//node[@text="Wait"]')
        if ($systemWait) { Tap $systemWait; continue }
        $node = $doc.SelectSingleNode($xpath)
        if ($node) { return $node }
        $failure = $doc.SelectSingleNode('//node[contains(@text,"Could not import")]')
        if ($failure) { throw $failure.text }
        if ($Scroll -and $doc.hierarchy.node.bounds -match '\[0,0\]\[(\d+),(\d+)\]') {
            $x = [int]([int]$Matches[1]*0.5)
            $h = [int]$Matches[2]
            Device shell input swipe $x ([int]($h*0.77)) $x ([int]($h*0.33)) 450
        }
    } while ((Get-Date) -lt $deadline)
    throw "Control missing: $xpath"
}

function Capture($screenName) {
    if (!$Screenshots) { return }
    New-Item -ItemType Directory -Force -Path $Screenshots | Out-Null
    $devicePath = '/data/local/tmp/morph-screen.png'
    Device shell screencap -p $devicePath
    Device pull $devicePath (Join-Path $Screenshots "$screenName.png")
}

Device push $fixture $remote
Device shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$remote"
Device shell am start -S -n com.morph.app/.MainActivity
Tap (Find-Control '//node[@text="Choose files"]')
$doc = Screen
if (-not $doc.SelectSingleNode("//node[@text='$name']")) {
    Tap (Find-Control '//node[@content-desc="Show roots"]')
    Tap (Find-Control '//node[@text="Downloads"]')
    Tap (Find-Control '//node[@content-desc="Search"]')
    Device shell input text $name
    Device shell input keyevent KEYCODE_ENTER
}
Tap (Find-Control "//node[@resource-id='android:id/title' and @text='$name']")
$null = Find-Control "//node[@package='com.morph.app' and @text='$name']" -Scroll
"Picked $name from Android DocumentsUI and confirmed MORPH displayed it."
Capture converter
if ($CapKiB) {
    Tap (Find-Control '(//node[@text="Exact size target"]/following::node[@checkable="true"])[1]' -Scroll)
    $preset = if ($CapKiB -eq 50) {'Exam 50 KB'} else {'100 KB'}
    Tap (Find-Control "//node[@text='$preset']" -Scroll)
}
$timer = [Diagnostics.Stopwatch]::StartNew()
Tap (Find-Control '//node[@text="Convert & compress"]' -Scroll)
$doc = Screen
$permission = $doc.SelectSingleNode('//node[@resource-id="com.android.permissioncontroller:id/permission_allow_button"]')
if ($permission) { Tap $permission }
$null = Find-Control "//node[@text='$name']"
$null = Find-Control "//node[@text='$name']/parent::node/descendant::node[starts-with(@text,'COMPLETE')]"
$timer.Stop()
"Conversion completed for $name after $([math]::Round($timer.Elapsed.TotalSeconds,2)) seconds including UI polling."
if ($CapKiB) {
    $exports = Device shell ls -l /sdcard/Download/MORPH
    $observed = $exports | Where-Object { $_ -match [regex]::Escape([IO.Path]::GetFileNameWithoutExtension($name)) } | Select-Object -First 1
    if ($observed -notmatch '\s(\d+)\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s') { throw 'Missing exported fixture or its byte count.' }
    $outputSize = [long]$Matches[1]
    if ($outputSize -lt 1 -or $outputSize -gt $CapKiB * 1024) { throw "Output size exceeds the requested cap: $outputSize" }
    "Output size: $outputSize bytes ($CapKiB KiB cap)."
}
if ($Screenshots) {
    Capture queue
    Tap (Find-Control '//node[@text="Vault"]')
    $null = Find-Control "//node[@text='$name']" -Scroll
    Capture vault
    Tap (Find-Control '//node[@text="Inspect"]' -Scroll)
    $null = Find-Control '//node[@text="Before / after"]'
    Capture result
}
