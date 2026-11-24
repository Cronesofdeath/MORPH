param([Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Archive).Path)
try {
    $libraries = @($zip.Entries | Where-Object { $_.FullName -match '^(lib|jni)/arm64-v8a/.+\.so$' })
    if ($libraries.Count -eq 0) { throw "No ARM64 native libraries found in $Archive" }
    foreach ($entry in $libraries) {
        $stream = $entry.Open()
        $memory = [IO.MemoryStream]::new()
        try {
            $stream.CopyTo($memory)
            $bytes = $memory.ToArray()
            if ([BitConverter]::ToUInt32($bytes,0) -ne 0x464c457f -or $bytes[4] -ne 2 -or $bytes[5] -ne 1 -or [BitConverter]::ToUInt16($bytes,18) -ne 183) { throw "Invalid ARM64 ELF header" }
            $offset = [BitConverter]::ToUInt64($bytes,32)
            $size = [BitConverter]::ToUInt16($bytes,54)
            $count = [BitConverter]::ToUInt16($bytes,56)
            $loads = @(for ($i=0; $i -lt $count; $i++) {
                $p = [int]($offset + $i * $size)
                if ([BitConverter]::ToUInt32($bytes,$p) -eq 1) {
                    $align = [BitConverter]::ToUInt64($bytes,$p+48)
                    $fileOffset = [BitConverter]::ToUInt64($bytes,$p+8)
                    $virtual = [BitConverter]::ToUInt64($bytes,$p+16)
                    if ($align -lt 16384 -or ($fileOffset % 16384) -ne ($virtual % 16384)) { throw "$($entry.FullName) fails 16 KB alignment ($align)" }
                    $align
                }
            })
            if (!$loads.Count) { throw "No loadable segments found" }
            "$($entry.Name) PASS ($($loads -join ', '))"
        } finally { $stream.Dispose(); $memory.Dispose() }
    }
    "$($libraries.Count) ARM64 libraries passed"
} finally { $zip.Dispose() }
