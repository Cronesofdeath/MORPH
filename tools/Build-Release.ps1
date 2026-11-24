param([switch]$AllowDevSign)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $root
try {
    $gradleArgs = @(':app:assembleRelease','-PcompactArm64=true','--console=plain')
    if ($AllowDevSign) { $gradleArgs += '-PallowDevSign=true' }
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        & .\gradlew.bat @gradleArgs
    } else {
        & bash ./gradlew @gradleArgs
    }
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    $apk = 'app/build/compact-arm64/outputs/apk/release/app-release.apk'
    & (Join-Path $PSScriptRoot 'Test-ReleasePackage.ps1') -Archive $apk
    $destination = Join-Path 'build/release' $(if ($AllowDevSign) {'development'} else {'production'})
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $output = Join-Path $destination 'MORPH-arm64.apk'
    Copy-Item -LiteralPath $apk -Destination $output -Force
    $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash.ToLowerInvariant()
    "$sha  MORPH-arm64.apk" | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt') -Encoding ascii
    Get-Item -LiteralPath $output
} finally { Pop-Location }
