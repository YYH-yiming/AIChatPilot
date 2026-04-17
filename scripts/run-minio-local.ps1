param(
    [string]$MinioExe = "D:\MinIO\minio.exe",
    [string]$DataDir = "D:\MinIO\data",
    [string]$ConsoleAddress = ":9001",
    [string]$RootUser = "minioadmin",
    [string]$RootPassword = "minioadmin123"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Test-Path $MinioExe)) {
    throw "MinIO executable not found: $MinioExe"
}

if (-not (Test-Path $DataDir)) {
    New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
}

[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_USER", $RootUser, "Process")
[System.Environment]::SetEnvironmentVariable("MINIO_ROOT_PASSWORD", $RootPassword, "Process")

Write-Host "Starting MinIO..."
Write-Host "API:     http://localhost:9000"
Write-Host "Console: http://localhost$ConsoleAddress"
Write-Host "Data:    $DataDir"

& $MinioExe server $DataDir --console-address $ConsoleAddress
