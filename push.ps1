# ===================================================================
# 一键上传到 GitHub
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\push.ps1 -Message "feat(auth): add JWT login"
#   powershell -ExecutionPolicy Bypass -File .\push.ps1 -Message "..." -NoPush
#
# 首次使用前做一次（只需一次）：
#   1) 把公钥加到 GitHub：https://github.com/settings/keys
#      查看公钥：Get-Content ~\.ssh\id_ed25519.pub
#   2) 在 GitHub 建一个空仓库（不要勾 README / .gitignore / license）
#   3) 执行：git remote add origin git@github.com:你的用户名/仓库名.git
#
# 网络说明：本机 github.com:443 被封锁，已通过 ~/.ssh/config 把
#          github.com 映射到 ssh.github.com:443，因此照样能推送。
# ===================================================================
param(
    [Parameter(Mandatory = $true)]
    [string]$Message,

    [switch]$NoPush,
    [switch]$SkipSecretCheck
)

Set-Location $PSScriptRoot

# 刻意不设 $ErrorActionPreference = "Stop"：
# git 在"没有远端"这类正常场景下也会往 stderr 写内容，
# 设成 Stop 会让脚本在给出提示前就中断。
$ErrorActionPreference = "Continue"

function Invoke-Git {
    param([string[]]$GitArgs)
    $out = & git @GitArgs 2>&1
    return @{ Code = $LASTEXITCODE; Out = (($out | Out-String).Trim()) }
}

function Write-Step($n, $text) {
    Write-Host ""
    Write-Host "=== $n. $text ===" -ForegroundColor Cyan
}

# ---------------------------------------------------------------
Write-Step 1 "检查远端配置"
$r = Invoke-Git @("remote", "get-url", "origin")
if ($r.Code -ne 0) {
    Write-Host "[X] 还没有配置远端仓库。" -ForegroundColor Red
    Write-Host ""
    Write-Host "    请先做这三步（只需一次）：" -ForegroundColor Yellow
    Write-Host "    1) 添加公钥到 GitHub：https://github.com/settings/keys" -ForegroundColor Yellow
    Write-Host "       公钥内容：" -ForegroundColor Gray
    $pub = "$env:USERPROFILE\.ssh\id_ed25519.pub"
    if (Test-Path $pub) { Get-Content $pub | ForEach-Object { Write-Host "       $_" -ForegroundColor White } }
    else { Write-Host "       (未找到 $pub)" -ForegroundColor Red }
    Write-Host "    2) 在 GitHub 建一个空仓库，不要勾 README / .gitignore / license" -ForegroundColor Yellow
    Write-Host "    3) 执行：git remote add origin git@github.com:你的用户名/仓库名.git" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "    自测连通：ssh -T git@github.com" -ForegroundColor Gray
    exit 1
}
Write-Host "[OK] origin = $($r.Out)"

# ---------------------------------------------------------------
Write-Step 2 "暂存改动"
$null = Invoke-Git @("add", "-A")
$stagedNames = (Invoke-Git @("diff", "--cached", "--name-only")).Out
if (-not $stagedNames) {
    Write-Host "[!] 没有需要提交的改动。" -ForegroundColor Yellow
    if ($NoPush) { exit 0 }
    Write-Host "    直接尝试推送..." -ForegroundColor Gray
    $p = Invoke-Git @("push")
    if ($p.Code -eq 0) { Write-Host "[OK] 推送完成" -ForegroundColor Green; exit 0 }
    Write-Host "[X] $($p.Out)" -ForegroundColor Red
    exit 1
}
$stagedNames -split "`n" | ForEach-Object { Write-Host "    $_" }

# ---------------------------------------------------------------
if (-not $SkipSecretCheck) {
    Write-Step 3 "敏感信息检查"
    $diff = (Invoke-Git @("diff", "--cached")).Out

    $rules = @(
        @{ Name = "明文口令";   Pattern = '(?i)(password|passwd|pwd)\s*[:=]\s*["'']?[A-Za-z0-9_@#!\.\-]{6,}' },
        @{ Name = "API Key";    Pattern = 'sk-[A-Za-z0-9]{20,}' },
        @{ Name = "私钥内容";    Pattern = 'BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY' },
        # 不直接写弱口令字面量：否则脚本扫描自己时会误报
        @{ Name = "口令疑似生日"; Pattern = '(?i)(password|passwd|pwd)\s*[:=]\s*["'']?(19|20)\d{2}' }
    )
    # 占位符与示例值不算
    $allow = '\$\{|\$env:|<你的|<your|example|placeholder|CHANGE_ME|your-password|你的密码'

    $found = @()
    foreach ($rule in $rules) {
        $hits = $diff -split "`n" | Select-String -Pattern $rule.Pattern
        $real = $hits | Where-Object { $_.Line -notmatch $allow }
        if ($real) { $found += $rule.Name }
    }

    if ($found.Count -gt 0) {
        Write-Host "[X] 检测到可能的敏感信息：$($found -join '、')" -ForegroundColor Red
        Write-Host "    真实凭据请放到 application-local.yml（已被 .gitignore 排除）。" -ForegroundColor Yellow
        Write-Host "    确认无误可加 -SkipSecretCheck 跳过本次检查。" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "[OK] 未检测到明显敏感信息"
} else {
    Write-Step 3 "敏感信息检查"
    Write-Host "[i] 已跳过（-SkipSecretCheck）" -ForegroundColor Yellow
}

# ---------------------------------------------------------------
Write-Step 4 "提交"
$c = Invoke-Git @("commit", "-q", "-m", $Message)
if ($c.Code -ne 0) {
    Write-Host "[X] 提交失败：$($c.Out)" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] $Message"

if ($NoPush) {
    Write-Host ""
    Write-Host "[i] 已跳过推送（-NoPush）。需要时执行：git push" -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------
Write-Step 5 "推送到 GitHub"
$up = Invoke-Git @("rev-parse", "--abbrev-ref", "@{upstream}")
if ($up.Code -eq 0) { $p = Invoke-Git @("push") } else { $p = Invoke-Git @("push", "-u", "origin", "main") }

if ($p.Code -eq 0) {
    Write-Host "[OK] 上传成功" -ForegroundColor Green
    Write-Host ""
    $logOut = (Invoke-Git @("log", "--oneline", "-3")).Out
    $logOut -split "`n" | ForEach-Object { Write-Host "    $_" }
    exit 0
}

Write-Host "[X] 推送失败：$($p.Out)" -ForegroundColor Red
Write-Host ""
Write-Host "常见原因与处理：" -ForegroundColor Yellow
Write-Host "  1) 公钥未加到 GitHub" -ForegroundColor Yellow
Write-Host "     ssh -T git@github.com     # 出现 successfully authenticated 即正常" -ForegroundColor Gray
Write-Host "  2) 建仓库时勾了 README，远端有本地没有的提交" -ForegroundColor Yellow
Write-Host "     git pull --rebase origin main   然后再 push" -ForegroundColor Gray
Write-Host "  3) 远端地址写错（应用 SSH 格式，不是 https）" -ForegroundColor Yellow
Write-Host "     git remote set-url origin git@github.com:用户名/仓库名.git" -ForegroundColor Gray
Write-Host "  4) 网络：github.com 直连被封锁，本项目已配好 ssh.github.com:443" -ForegroundColor Yellow
Write-Host "     检查 ~/.ssh/config 中的 Host github.com 段落" -ForegroundColor Gray
exit 1
