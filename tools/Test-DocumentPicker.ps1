param([Parameter(Mandatory=$true)][string]$Serial)
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
Tap (Find-Control '//node[@text="Convert & compress"]' -Scroll)
$doc = Screen
$permission = $doc.SelectSingleNode('//node[@resource-id="com.android.permissioncontroller:id/permission_allow_button"]')
if ($permission) { Tap $permission }
$null = Find-Control "//node[@text='$name']"
$null = Find-Control "//node[@text='$name']/parent::node/descendant::node[starts-with(@text,'COMPLETE')]"
"Conversion completed for $name."
