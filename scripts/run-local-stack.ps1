param(
    [string]$Profile = "local",
    [string]$EnvFile = "",
    [string[]]$Services = @("kafka", "es", "minio", "user", "knowledge", "gateway"),
    [switch]$SkipEnv,
    [int]$InfraDelaySeconds = 4,
    [int]$ServiceDelaySeconds = 8
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RepoRoot = Split-Path -Parent $PSScriptRoot

if (-not $EnvFile) {
    $preferredEnv = Join-Path $RepoRoot ".env.$Profile"
    $fallbackEnv = Join-Path $RepoRoot ".env.$Profile.example"
    $EnvFile = if (Test-Path $preferredEnv) { $preferredEnv } else { $fallbackEnv }
} elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $RepoRoot $EnvFile
}

$serviceDefinitions = [ordered]@{
    kafka = @{
        script = Join-Path $PSScriptRoot "run-kafka-local.ps1"
        delay  = $InfraDelaySeconds
        title  = "AIChatPilot-Kafka"
    }
    es = @{
        script = Join-Path $PSScriptRoot "run-elasticsearch-local.ps1"
        delay  = $InfraDelaySeconds
        title  = "AIChatPilot-ES"
    }
    minio = @{
        script = Join-Path $PSScriptRoot "run-minio-local.ps1"
        delay  = $InfraDelaySeconds
        title  = "AIChatPilot-MinIO"
    }
    user = @{
        script = Join-Path $PSScriptRoot "run-user.ps1"
        delay  = $ServiceDelaySeconds
        title  = "AIChatPilot-User"
    }
    knowledge = @{
        script = Join-Path $PSScriptRoot "run-knowledge.ps1"
        delay  = $ServiceDelaySeconds
        title  = "AIChatPilot-Knowledge"
    }
    gateway = @{
        script = Join-Path $PSScriptRoot "run-gateway.ps1"
        delay  = $ServiceDelaySeconds
        title  = "AIChatPilot-Gateway"
    }
}

function Start-ServiceWindow {
    param(
        [string]$ServiceKey,
        [hashtable]$Definition
    )

    $scriptPath = $Definition.script
    if (-not (Test-Path $scriptPath)) {
        throw "Service script not found for '$ServiceKey': $scriptPath"
    }

    $argumentList = @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-Command"
    )

    $commandParts = @(
        "`$Host.UI.RawUI.WindowTitle = '$($Definition.title)';",
        "& '$scriptPath'"
    )

    if ($ServiceKey -in @("user", "knowledge", "gateway")) {
        $commandParts += "-Profile '$Profile'"
        if ($SkipEnv) {
            $commandParts += "-SkipEnv"
        } else {
            $commandParts += "-EnvFile '$EnvFile'"
        }
    }

    $argumentList += ($commandParts -join " ")

    Start-Process -FilePath "powershell.exe" `
        -WorkingDirectory $RepoRoot `
        -ArgumentList $argumentList | Out-Null

    Write-Host "Started $ServiceKey using $scriptPath"
}

foreach ($service in $Services) {
    if (-not $serviceDefinitions.Contains($service)) {
        throw "Unknown service '$service'. Supported values: $($serviceDefinitions.Keys -join ', ')"
    }

    $definition = $serviceDefinitions[$service]
    Start-ServiceWindow -ServiceKey $service -Definition $definition

    if ($definition.delay -gt 0) {
        Start-Sleep -Seconds $definition.delay
    }
}

Write-Host ""
Write-Host "Local stack startup commands dispatched."
Write-Host "Profile : $Profile"
Write-Host "EnvFile : $EnvFile"
Write-Host "Services: $($Services -join ', ')"
Write-Host ""
Write-Host "Default local endpoints:"
Write-Host "  Kafka    localhost:9095"
Write-Host "  ES       http://localhost:9200"
Write-Host "  MinIO    http://localhost:9000"
Write-Host "  User     http://localhost:8081"
Write-Host "  Knowledge http://localhost:8082"
Write-Host "  Gateway  http://localhost:8080"
