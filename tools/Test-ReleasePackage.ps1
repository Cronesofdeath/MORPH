param(
    [Parameter(Mandatory=$true)][string]$Archive,
    [string]$MergedManifest,
    [string]$AndroidSdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
if (!$AndroidSdk -and $IsWindows) {
    $AndroidSdk = [Environment]::GetEnvironmentVariable('ANDROID_HOME','User')
}
if (!$AndroidSdk) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
$apk = Get-Item -LiteralPath $Archive
if (!$MergedManifest) {
    $buildRoot = $apk.Directory.Parent.Parent.Parent
    $MergedManifest = Join-Path $buildRoot.FullName 'intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml'
}
$xml = [xml](Get-Content -Raw -LiteralPath $MergedManifest)
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$blocked = @('android.permission.INTERNET','android.permission.ACCESS_NETWORK_STATE')
$permissions = @($xml.manifest.ChildNodes | Where-Object {
    $_.NodeType -eq 'Element' -and $_.LocalName.StartsWith('uses-permission')
} | ForEach-Object { $_.GetAttribute('name',$androidNamespace) })
if (!$permissions.Count -or ($permissions | Where-Object { $_ -in $blocked })) {
    throw 'Merged manifest permission verification failed.'
}
'Merged manifest permissions:'
$permissions | Sort-Object -Unique

$signerName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'apksigner.bat' } else { 'apksigner' }
$aaptName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'aapt.exe' } else { 'aapt' }
$buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName $signerName) } |
    Sort-Object Name -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android SDK build-tools with apksigner is required.' }
& (Join-Path $buildTools.FullName $signerName) verify --verbose --print-certs $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$dump = & (Join-Path $buildTools.FullName $aaptName) dump permissions $apk.FullName
if ($LASTEXITCODE -ne 0 -or !$dump -or ($dump | Select-String 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)')) {
    throw 'Packaged manifest permission verification failed.'
}
'Packaged APK permissions:'
$dump
& (Join-Path $PSScriptRoot 'Test-Arm64Alignment.ps1') $apk.FullName

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
try {
    $dex = @($zip.Entries | Where-Object FullName -match '^classes(\d+)?\.dex$')
    $native = @($zip.Entries | Where-Object FullName -match '^lib/arm64-v8a/[^/]+\.so$')
    $otherAbis = @($zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' -and $_.FullName -notmatch '^lib/arm64-v8a/' })
    if (!$dex.Count -or !$native.Count -or $otherAbis.Count) { throw 'Expected DEX and a single ARM64 ABI.' }
    foreach ($profile in @('assets/dexopt/baseline.prof','assets/dexopt/baseline.profm')) {
        $entry = $zip.GetEntry($profile)
        if (!$entry -or !$entry.Length) { throw "Missing ART profile $profile" }
        "$profile $($entry.Length) bytes"
    }
    $dexBytes = ($dex | ForEach-Object Length | Measure-Object -Sum).Sum
    $nativeBytes = ($native | ForEach-Object Length | Measure-Object -Sum).Sum
    "APK bytes: $($apk.Length)"
    "DEX count: $($dex.Count)"
    "DEX bytes (uncompressed): $dexBytes"
    "ARM64 SO bytes (uncompressed): $nativeBytes"
} finally { $zip.Dispose() }
