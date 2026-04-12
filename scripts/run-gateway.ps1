param(
    [string]$Profile = "local",
    [string]$EnvFile = "",
    [switch]$SkipEnv
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot

function Import-EnvFile {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        throw "Env file not found: $Path"
    }

    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }
        $parts = $line.Split("=", 2)
        if ($parts.Count -ne 2) {
            return
        }
        [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
    }
}

if (-not $SkipEnv) {
    if (-not $EnvFile) {
        $EnvFile = Join-Path $RepoRoot ".env.$Profile.example"
    } elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
        $EnvFile = Join-Path $RepoRoot $EnvFile
    }
    Import-EnvFile -Path $EnvFile
}

[System.Environment]::SetEnvironmentVariable("SERVER_PORT", $null, "Process")
[System.Environment]::SetEnvironmentVariable("GATEWAY_PORT", $null, "Process")
[System.Environment]::SetEnvironmentVariable("SPRING_PROFILES_ACTIVE", $Profile, "Process")

Push-Location $RepoRoot
try {
    mvn -pl aichatpilot-gateway -am -DskipTests install
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    mvn -pl aichatpilot-gateway spring-boot:run
}
finally {
    Pop-Location
}
