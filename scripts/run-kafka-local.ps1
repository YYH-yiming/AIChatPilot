param(
    [string]$KafkaRoot = "E:\kafka_2.13-3.9.2",
    [string]$ConfigFile = "",
    [string]$BootstrapServers = "localhost:9095",
    [switch]$RestartIfRunning,
    [switch]$ResetDataDir
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

function Wait-ForPortsRelease {
    param(
        [int[]]$Ports,
        [int]$TimeoutSeconds = 15
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $occupied = $Ports | Where-Object { $null -ne (Get-ListeningProcessInfo -Port $_) }
        if ($occupied.Count -eq 0) {
            return $true
        }
        Start-Sleep -Milliseconds 400
    }

    $remaining = $Ports | Where-Object { $null -ne (Get-ListeningProcessInfo -Port $_) }
    return $remaining.Count -eq 0
}

function Get-ListenerPorts {
    param(
        [string]$Listeners
    )

    if (-not $Listeners) {
        return @()
    }

    $ports = New-Object System.Collections.Generic.List[int]
    foreach ($entry in ($Listeners -split ",")) {
        $trimmed = $entry.Trim()
        if (-not $trimmed) {
            continue
        }

        if ($trimmed -match ":(\d+)$") {
            $ports.Add([int]$Matches[1])
        }
    }
    return $ports.ToArray()
}

function Ensure-DirectoryWritable {
    param(
        [string]$DirectoryPath
    )

    if (-not (Test-Path $DirectoryPath)) {
        New-Item -ItemType Directory -Path $DirectoryPath -Force | Out-Null
    }

    $probeFile = Join-Path $DirectoryPath ".aichatpilot-write-test"
    try {
        Set-Content -Path $probeFile -Value "ok" -Encoding ASCII
        Remove-Item -LiteralPath $probeFile -Force
    }
    catch {
        throw "Kafka log directory is not writable: $DirectoryPath. $($_.Exception.Message)"
    }
}

function New-EffectiveKafkaConfig {
    param(
        [string]$SourceConfig,
        [string[]]$Lines,
        [bool]$DisableLogCleaner
    )

    if (-not $DisableLogCleaner) {
        return $SourceConfig
    }

    $tempConfig = Join-Path $env:TEMP ("aichatpilot-kafka-" + [System.IO.Path]::GetFileName($SourceConfig))
    $outputLines = New-Object System.Collections.Generic.List[string]
    $cleanerConfigured = $false

    foreach ($line in $Lines) {
        if ($line.Trim().StartsWith("log.cleaner.enable=")) {
            $outputLines.Add("log.cleaner.enable=false")
            $cleanerConfigured = $true
        }
        else {
            $outputLines.Add($line)
        }
    }

    if (-not $cleanerConfigured) {
        $outputLines.Add("log.cleaner.enable=false")
    }

    Set-Content -Path $tempConfig -Value $outputLines -Encoding ASCII
    return $tempConfig
}

if (-not $ConfigFile) {
    $kraftConfig = Join-Path $KafkaRoot "config\kraft\local-kraft-9095.properties"
    $fallbackConfig = Join-Path $KafkaRoot "config\server.properties"
    $ConfigFile = if (Test-Path $kraftConfig) { $kraftConfig } else { $fallbackConfig }
}

$KafkaStartBat = Join-Path $KafkaRoot "bin\windows\kafka-server-start.bat"
$KafkaStorageBat = Join-Path $KafkaRoot "bin\windows\kafka-storage.bat"

if (-not (Test-Path $KafkaStartBat)) {
    throw "Kafka startup script not found: $KafkaStartBat"
}

if (-not (Test-Path $ConfigFile)) {
    throw "Kafka config file not found: $ConfigFile"
}

if (-not (Test-Path $KafkaStorageBat)) {
    throw "Kafka storage script not found: $KafkaStorageBat"
}

function Get-PropertyValue {
    param(
        [string[]]$Lines,
        [string]$Name
    )

    foreach ($line in $Lines) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        if ($trimmed.StartsWith("$Name=")) {
            return $trimmed.Substring($Name.Length + 1).Trim()
        }
    }
    return $null
}

$configLines = Get-Content $ConfigFile
$processRoles = Get-PropertyValue -Lines $configLines -Name "process.roles"
$logDirs = Get-PropertyValue -Lines $configLines -Name "log.dirs"
$listeners = Get-PropertyValue -Lines $configLines -Name "listeners"
$listenerPorts = Get-ListenerPorts -Listeners $listeners

if ($listenerPorts.Count -gt 0) {
    $occupiedPorts = @($listenerPorts | ForEach-Object { Get-ListeningProcessInfo -Port $_ } | Where-Object { $null -ne $_ })
    if ($occupiedPorts.Count -gt 0) {
        $uniquePids = @($occupiedPorts.ProcessId | Sort-Object -Unique)
        $allJava = ($occupiedPorts | Where-Object { $_.ProcessName -ieq "java" }).Count -eq $occupiedPorts.Count

        if ($allJava -and -not $RestartIfRunning -and $occupiedPorts.Count -eq $listenerPorts.Count -and $uniquePids.Count -eq 1) {
            Write-Host "Kafka is already listening on ports $($listenerPorts -join ', ') (PID=$($uniquePids[0])). Reusing existing instance."
            exit 0
        }

        if ($allJava -and $RestartIfRunning) {
            foreach ($pid in $uniquePids) {
                Write-Host "Stopping existing Kafka Java process PID=$pid ..."
                Stop-Process -Id $pid -Force -ErrorAction Stop
            }
            if (-not (Wait-ForPortsRelease -Ports $listenerPorts -TimeoutSeconds 15)) {
                throw "Kafka ports $($listenerPorts -join ', ') did not become available after stopping existing process."
            }
        }
        else {
            $descriptions = $occupiedPorts | ForEach-Object {
                "port $($_.Port) -> $($_.ProcessName) (PID=$($_.ProcessId))"
            }
            throw "Kafka listener ports are already in use: $($descriptions -join '; ')"
        }
    }
}

if ($processRoles -and $processRoles -match "controller") {
    if (-not $logDirs) {
        throw "KRaft config is missing log.dirs: $ConfigFile"
    }

    $primaryLogDir = (($logDirs -split ",")[0].Trim()).Replace("/", "\")
    if ($ResetDataDir -and (Test-Path $primaryLogDir)) {
        Write-Host "Resetting Kafka data directory: $primaryLogDir"
        Remove-Item -LiteralPath $primaryLogDir -Recurse -Force
    }

    Ensure-DirectoryWritable -DirectoryPath $primaryLogDir

    $effectiveConfig = New-EffectiveKafkaConfig -SourceConfig $ConfigFile -Lines $configLines -DisableLogCleaner $true
    if ($effectiveConfig -ne $ConfigFile) {
        $ConfigFile = $effectiveConfig
        $configLines = Get-Content $ConfigFile
    }

    $metaFile = Join-Path $primaryLogDir "meta.properties"
    if (-not (Test-Path $metaFile)) {
        $clusterId = (& $KafkaStorageBat random-uuid).Trim()
        Write-Host "Formatting KRaft storage with cluster.id=$clusterId"
        & $KafkaStorageBat format -t $clusterId -c $ConfigFile
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
}

Write-Host "Starting Kafka..."
Write-Host "Root:   $KafkaRoot"
Write-Host "Config: $ConfigFile"
Write-Host "Broker: $BootstrapServers"

Push-Location $KafkaRoot
try {
    & $KafkaStartBat $ConfigFile
}
finally {
    Pop-Location
}
