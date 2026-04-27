param(
    [string]$KafkaRoot = "E:\kafka_2.13-3.9.2",
    [string]$ConfigFile = "",
    [string]$BootstrapServers = "localhost:9095"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

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

if ($processRoles -and $processRoles -match "controller") {
    if (-not $logDirs) {
        throw "KRaft config is missing log.dirs: $ConfigFile"
    }

    $metaFile = Join-Path (($logDirs -split ",")[0].Trim()) "meta.properties"
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
