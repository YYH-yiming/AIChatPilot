param(
    [string]$Profile = "local",
    [string]$EnvFile = "",
    [switch]$SkipEnv
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

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
        $preferredEnv = Join-Path $RepoRoot ".env.$Profile"
        $fallbackEnv = Join-Path $RepoRoot ".env.$Profile.example"
        $EnvFile = if (Test-Path $preferredEnv) { $preferredEnv } else { $fallbackEnv }
    } elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
        $EnvFile = Join-Path $RepoRoot $EnvFile
    }
    Import-EnvFile -Path $EnvFile
}

[System.Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8", "Process")
[System.Environment]::SetEnvironmentVariable("SPRING_PROFILES_ACTIVE", $Profile, "Process")

Push-Location $RepoRoot
try {
    mvn -pl aichatpilot-analytics -am -DskipTests install
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    mvn -pl aichatpilot-analytics spring-boot:run
}
finally {
    Pop-Location
}
