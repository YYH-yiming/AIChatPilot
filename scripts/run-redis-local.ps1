param(
    [string]$RedisRoot = "D:\Redis",
    [string]$ConfigFile = "",
    [switch]$RestartIfRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-ListeningProcessInfo {
    param(
        [int]$Port
    )

    try {
        $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
            Select-Object -First 1
        if ($null -eq $connection) {
            return $null
        }

        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        return [pscustomobject]@{
            Port = $Port
            ProcessId = $connection.OwningProcess
            ProcessName = if ($process) { $process.ProcessName } else { "<unknown>" }
        }
    }
    catch {
        $netstatLine = netstat -ano -p tcp |
            Select-String -Pattern "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$" |
            Select-Object -First 1

        if ($null -eq $netstatLine) {
            return $null
        }

        $processId = [int]$netstatLine.Matches[0].Groups[1].Value
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        return [pscustomobject]@{
            Port = $Port
            ProcessId = $processId
            ProcessName = if ($process) { $process.ProcessName } else { "<unknown>" }
        }
    }
}

function Wait-ForPortRelease {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 10
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Get-ListeningProcessInfo -Port $Port)) {
            return $true
        }
        Start-Sleep -Milliseconds 300
    }
    return $null -eq (Get-ListeningProcessInfo -Port $Port)
}

function Get-RedisServiceHint {
    try {
        $service = Get-Service -Name "Redis" -ErrorAction Stop
        return "Detected Windows service '$($service.Name)' in state '$($service.Status)'. Restart it from an elevated PowerShell with: Stop-Service Redis; Start-Service Redis"
    }
    catch {
        return "If this Redis process was started as a Windows service or from an elevated shell, restart it from an elevated PowerShell."
    }
}

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

$portNumber = [int]$port
$existing = Get-ListeningProcessInfo -Port $portNumber
if ($null -ne $existing) {
    $isRedisProcess = $existing.ProcessName -ieq "redis-server"
    if ($isRedisProcess -and -not $RestartIfRunning) {
        Write-Host "Redis is already listening on port $portNumber (PID=$($existing.ProcessId)). Reusing existing instance."
        exit 0
    }

    if (-not $isRedisProcess) {
        throw "Port $portNumber is already in use by process '$($existing.ProcessName)' (PID=$($existing.ProcessId))."
    }

    Write-Host "Stopping existing Redis process on port $portNumber (PID=$($existing.ProcessId))..."
    try {
        Stop-Process -Id $existing.ProcessId -Force -ErrorAction Stop
    }
    catch {
        throw "Failed to stop Redis PID=$($existing.ProcessId): $($_.Exception.Message). $(Get-RedisServiceHint)"
    }
    if (-not (Wait-ForPortRelease -Port $portNumber -TimeoutSeconds 10)) {
        throw "Redis port $portNumber did not become available after stopping PID=$($existing.ProcessId)."
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
