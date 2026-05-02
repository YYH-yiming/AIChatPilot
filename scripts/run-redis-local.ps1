param(
    [string]$RedisRoot = "D:\Redis",
    [string]$ConfigFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RedisExe = Join-Path $RedisRoot "redis-server.exe"
if (-not (Test-Path $RedisExe)) {
    throw "Redis executable not found: $RedisExe"
}

if (-not $ConfigFile) {
    $candidates = @(
        (Join-Path $RedisRoot "redis.windows.conf"),
        (Join-Path $RedisRoot "redis.conf"),
        (Join-Path $RedisRoot "conf\redis.conf")
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            $ConfigFile = $candidate
            break
        }
    }
} elseif (-not [System.IO.Path]::IsPathRooted($ConfigFile)) {
    $ConfigFile = Join-Path $RedisRoot $ConfigFile
}

$port = "6379"
if ($ConfigFile -and (Test-Path $ConfigFile)) {
    foreach ($line in Get-Content $ConfigFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        if ($trimmed -match "^port\s+(\d+)$") {
            $port = $Matches[1]
            break
        }
    }
}

Write-Host "Starting Redis..."
Write-Host "Root: $RedisRoot"
Write-Host "Port: $port"
if ($ConfigFile) {
    Write-Host "Config: $ConfigFile"
} else {
    Write-Host "Config: <default>"
}

Push-Location $RedisRoot
try {
    if ($ConfigFile) {
        & $RedisExe $ConfigFile
    } else {
        & $RedisExe
    }
}
finally {
    Pop-Location
}
