# ===================================================================
# 一键启动（Windows PowerShell）
#
# 用法：
#   .\run-dev.ps1                  # 默认 Mock 桩，零 AI 消耗
#   .\run-dev.ps1 -RealAi          # 真实调用 DeepSeek（需要先设 DEEPSEEK_API_KEY）
#   .\run-dev.ps1 -TestOnly        # 只跑测试
#
# 设计意图：把"环境变量 + JDK 校验 + 启动参数"收在一处，
# 避免每次手工拼命令行导致跑错 profile 或忘了关 AI 消耗额度。
# ===================================================================
param(
    [switch]$RealAi,
    [switch]$TestOnly,
    [string]$JdkHome = "D:\java\jdk-21"
)

$ErrorActionPreference = "Stop"

# ---------- 1. 校验 JDK 21 ----------
if (-not (Test-Path "$JdkHome\bin\java.exe")) {
    # 退路：在常见安装位置里找
    $alt = @(
        "D:\java\jdk-21",
        "C:\Program Files\Eclipse Adoptium\jdk-21*",
        "C:\Program Files\Java\jdk-21*",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-21*"
    ) | ForEach-Object { Get-Item $_ -ErrorAction SilentlyContinue } |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
        Select-Object -First 1

    if ($alt) {
        $JdkHome = $alt.FullName
        Write-Host "[OK] 自动找到 JDK 21：$JdkHome" -ForegroundColor Green
    } else {
        Write-Host "[X] 未找到 JDK 21：$JdkHome" -ForegroundColor Red
        Write-Host "    请安装 JDK 21，或用 -JdkHome 指定路径。" -ForegroundColor Yellow
        Write-Host "    下载：https://adoptium.net/temurin/releases/?version=21" -ForegroundColor Yellow
        exit 1
    }
}

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
Write-Host "[OK] JAVA_HOME = $JdkHome"

$ver = & "$JdkHome\bin\java.exe" -version 2>&1 | Select-Object -First 1
Write-Host "[OK] $ver"

# ---------- 2. 凭据 ----------
# 凭据优先从 application-local.yml 读取（该文件不进仓库）。
# 若不存在，则要求用环境变量提供 —— 脚本里刻意不写死密码，避免密码进版本库。
$localConf = "src\main\resources\application-local.yml"
if (Test-Path $localConf) {
    Write-Host "[OK] 使用本地凭据文件：$localConf"
} elseif ($env:DB_PASSWORD) {
    Write-Host "[OK] 使用环境变量中的凭据（DB_PASSWORD 已设置）"
} else {
    Write-Host "[X] 未找到凭据，任选其一：" -ForegroundColor Red
    Write-Host "    1) 复制 src\main\resources\application-local.yml.example 为 application-local.yml 并填写" -ForegroundColor Yellow
    Write-Host '    2) 设置环境变量：$env:DB_PASSWORD = "你的密码"; $env:REDIS_PASSWORD = "你的密码"' -ForegroundColor Yellow
    exit 1
}

# ---------- 3. AI 通道 ----------
# 默认关闭：开发阶段保持零额度消耗（决策 D-12）
if ($RealAi) {
    if (-not $env:DEEPSEEK_API_KEY) {
        Write-Host "[X] -RealAi 需要先设置 DEEPSEEK_API_KEY" -ForegroundColor Red
        Write-Host '    例：$env:DEEPSEEK_API_KEY = "sk-xxxx"' -ForegroundColor Yellow
        exit 1
    }
    $env:AI_ENABLED = "true"
    Write-Host "[!] AI 真实调用已开启 —— 会消耗额度（当前上限 5 元/日）" -ForegroundColor Yellow
} else {
    $env:AI_ENABLED = "false"
    Write-Host "[OK] AI 走 Mock 桩（零消耗）"
}

# ---------- 4. 检查中间件 ----------
Write-Host ""
Write-Host "--- 中间件状态 ---"
foreach ($svc in @("MySQL80", "Redis")) {
    $s = Get-Service -Name $svc -ErrorAction SilentlyContinue
    if ($null -eq $s) {
        Write-Host "[X] 服务 $svc 不存在" -ForegroundColor Red
    } elseif ($s.Status -ne "Running") {
        Write-Host "[!] 服务 $svc 未运行，尝试启动..." -ForegroundColor Yellow
        Start-Service $svc
        Write-Host "[OK] 服务 $svc 已启动"
    } else {
        Write-Host "[OK] 服务 $svc 运行中"
    }
}

# ---------- 5. 执行 ----------
Write-Host ""
if ($TestOnly) {
    Write-Host "--- 运行测试（H2 内存库 + Mock 桩，不消耗额度）---" -ForegroundColor Cyan
    & mvn test
} else {
    Write-Host "--- 启动应用（profile=dev）---" -ForegroundColor Cyan
    Write-Host "    自检地址：http://localhost:8080/api/health" -ForegroundColor Gray
    Write-Host "    注意看返回里的 aiEnabled 字段，确认通道状态" -ForegroundColor Gray
    Write-Host ""
    & mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
}
