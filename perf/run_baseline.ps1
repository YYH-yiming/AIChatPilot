# perf/run_baseline.ps1 — 一键基线压测编排（阶段 A）。
#
# 前置：
#   1) 基础设施 + user/gateway/knowledge/agent/chat 全部已启动（参考 docs/本地测试指南_不依赖Docker.md）
#   2) A1 容量基线：在 gateway 进程环境里设 GATEWAY_RATE_LIMIT_ENABLED=false 再启动
#   3) eval/.venv 已建（脚本会自动激活；httpx 随 openai 已装）
#   4) 复制 perf/config.example.env -> perf/.env 并填好 PERF_KB_ID 等
#
# 用法：
#   pwsh perf/run_baseline.ps1                      # 跑全套（knowledge 链路 + 各端点扫描）
#   pwsh perf/run_baseline.ps1 -Mode agent          # 主链路换 agent 模式
#   pwsh perf/run_baseline.ps1 -Sweep "1,4,8,16"    # 自定义并发梯度
#   pwsh perf/run_baseline.ps1 -LimiterTest         # 仅跑 A2 限流有效性（需限流开启）
#
# 所有结果落 perf/results/，数字真实测得。

param(
    [string]$Mode = "knowledge",
    [string]$Sweep = "1,2,4,8,16,32",
    [int]$WaterfallReps = 30,
    [int]$RequestsPerLevel = 40,
    [switch]$LimiterTest
)

$ErrorActionPreference = "Stop"
$PerfDir = $PSScriptRoot
$RepoRoot = Split-Path $PerfDir -Parent

# ---- 载入 perf/.env ----
$envFile = Join-Path $PerfDir ".env"
if (Test-Path $envFile) {
    Write-Host "[perf] 载入 $envFile"
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $idx = $line.IndexOf("=")
            $k = $line.Substring(0, $idx).Trim()
            $v = $line.Substring($idx + 1).Trim()
            [System.Environment]::SetEnvironmentVariable($k, $v)
        }
    }
} else {
    Write-Host "[perf] 未发现 perf/.env，使用 common.py 默认值（可能需要 PERF_KB_ID）" -ForegroundColor Yellow
}

# ---- 选 python（优先 eval/.venv）----
$venvPy = Join-Path $RepoRoot "eval\.venv\Scripts\python.exe"
$py = if (Test-Path $venvPy) { $venvPy } else { "python" }
Write-Host "[perf] 使用 Python: $py"

function Invoke-Perf([string]$script, [string[]]$argv) {
    Write-Host "`n=== $script $($argv -join ' ') ===" -ForegroundColor Cyan
    & $py (Join-Path $PerfDir $script) @argv
}

if ($LimiterTest) {
    # A2：限流开启时，单账号高频打 ask，看 429 比例与窗口恢复
    Write-Host "[perf] A2 限流有效性测试（需 GATEWAY_RATE_LIMIT_ENABLED=true）" -ForegroundColor Yellow
    Invoke-Perf "bench_rest.py" @("--endpoint", "ask", "--concurrency", "1", "--requests", "200")
    return
}

Write-Host "[perf] 阶段 A 基线开始。提醒：A1 容量基线应已关限流（GATEWAY_RATE_LIMIT_ENABLED=false）。" -ForegroundColor Yellow

# S1 单链路瀑布：并发=1，多次取中位数（配合 parse_perf_logs.py 出各段耗时）
Invoke-Perf "sse_ttft_client.py" @("--mode", $Mode, "--concurrency", "1", "--requests", "$WaterfallReps")

# S2 SSE 流式并发扫描（TTFT/端到端 P95 拐点、SSE 并发悬崖）
Invoke-Perf "sse_ttft_client.py" @("--mode", $Mode, "--sweep", $Sweep, "--requests-per-level", "$RequestsPerLevel")

# S4 纯检索 / S5 纯问答 / S6 Agent 链路 并发扫描
Invoke-Perf "bench_rest.py" @("--endpoint", "search", "--sweep", $Sweep, "--requests-per-level", "$RequestsPerLevel")
Invoke-Perf "bench_rest.py" @("--endpoint", "ask",    "--sweep", $Sweep, "--requests-per-level", "$RequestsPerLevel")
Invoke-Perf "bench_rest.py" @("--endpoint", "agent",  "--sweep", $Sweep, "--requests-per-level", "$RequestsPerLevel")

# S3 非流式聊天
Invoke-Perf "bench_rest.py" @("--endpoint", "chat-sync", "--sweep", $Sweep, "--requests-per-level", "$RequestsPerLevel")

# S7 缓存命中（同一 query 连打，看精确缓存命中前后延迟差）
Invoke-Perf "bench_rest.py" @("--endpoint", "ask", "--concurrency", "1", "--requests", "20", "--same-query")

Write-Host "`n[perf] 基线跑完。结果在 perf/results/。" -ForegroundColor Green
Write-Host "[perf] 下一步：把各服务日志喂给 parse_perf_logs.py 得到链路瀑布：" -ForegroundColor Green
Write-Host "       $py perf/parse_perf_logs.py <knowledge.log> <agent.log> <chat.log>" -ForegroundColor Green
