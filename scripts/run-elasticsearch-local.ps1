param(
    [string]$EsRoot = "D:\GoogleDownload\elasticsearch-7.17.29",
    [string]$HeapSize = "512m"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$EsBat = Join-Path $EsRoot "bin\elasticsearch.bat"
if (-not (Test-Path $EsBat)) {
    throw "Elasticsearch startup script not found: $EsBat"
}

[System.Environment]::SetEnvironmentVariable("ES_JAVA_OPTS", "-Xms$HeapSize -Xmx$HeapSize", "Process")

Write-Host "Starting Elasticsearch..."
Write-Host "Root: $EsRoot"
Write-Host "Heap: $HeapSize"
Write-Host "URL:  http://localhost:9200"

Push-Location $EsRoot
try {
    & $EsBat
}
finally {
    Pop-Location
}
