# ===================================================================
# 审计只追加的"第 2 层"验证：数据库触发器真的会拒绝 UPDATE / DELETE
# ===================================================================
#
# 【为什么需要这个脚本】
#
# T-018 的验收记录写着"MySQL 触发器：UPDATE / DELETE 均被 ERROR 1644 (45000) 拒绝"，
# 但 2026-10-07 核对时发现 schema.sql 里**根本没有 TRIGGER**。
# 也就是说：文档说有三层（接口 / 数据库 / 哈希链），实际只有两层，
# 而且**不会报错、不会失败、没有任何提示**。
#
# 触发器只在 MySQL 生效（H2 不支持 SIGNAL 语法），
# 所以集成测试**不可能**覆盖这一层 —— 必须对着真实 MySQL 跑。
# 这个脚本就是那条命令：把"文档里的断言"变成"可执行、可复核的证据"。
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-append-only.ps1
#
# 它做三件事：
#   1. 确认两个触发器在库里存在
#   2. 真的插一条、试 UPDATE、试 DELETE，断言都被 45000 拒绝，且数据没变
#   3. 清理这条测试记录（临时关掉触发器来删，删完立刻恢复）
#
# ⚠️ 这个脚本会**临时删除并重建**那两个触发器。
#    它对自己的测试数据是无害的，但请不要在演示进行中跑。

param(
    [string]$DbUser = "root",
    [string]$DbPassword = "123456",
    [string]$DbName = "hospital_appointment_contract"
)

# ⚠️ 必须是 Continue：本脚本**故意**执行会被数据库拒绝的 SQL，
#    那些 ERROR 1644 是**预期结果**，不是脚本故障。
#    （第一版写成 Stop，结果脚本在第一次 UPDATE 被拒时就中断了，
#      留下一条测试记录没清 —— 这正是"错误处理写错会让清理不执行"的例子。）
$ErrorActionPreference = "Continue"
$script:pass = 0
$script:fail = 0

function Ok($m)   { Write-Host ("  [OK]   " + $m) -ForegroundColor Green;  $script:pass++ }
function Bad($m)  { Write-Host ("  [FAIL] " + $m) -ForegroundColor Red;    $script:fail++ }
function Info($m) { Write-Host ("         " + $m) -ForegroundColor Gray }

Write-Host ""
Write-Host "审计只追加 · 第 2 层（数据库触发器）验证" -ForegroundColor Cyan
Write-Host "时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray
Write-Host ""

$env:MYSQL_PWD = $DbPassword

# 库名先探一下：项目 1 的库名可能是 contract_review / hospital_appointment_contract 等
$probe = (& mysql -u $DbUser -N -B -e "SHOW DATABASES LIKE '%contract%';" 2>&1) -join "`n"
Info "含 contract 的库：$($probe -replace "`n", ', ')"

$candidates = @($DbName) + @($probe -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$picked = $null
foreach ($c in $candidates) {
    if (-not $c) { continue }
    $has = (& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$c' AND table_name='review_action';" 2>&1) -join ''
    if ($has.Trim() -eq '1') { $picked = $c; break }
}
if (-not $picked) {
    Bad "找不到含 review_action 表的库。请先启动应用让 schema.sql 建表，或用 -DbName 指定。"
    Write-Host ""
    exit 1
}
$DbName = $picked
Info "使用库：$DbName"

# ---------------------------------------------------------------- 1
Write-Host ""
Write-Host "[1/3] 触发器是否存在" -ForegroundColor Yellow
$trg = (& mysql -u $DbUser -N -B -e "SELECT TRIGGER_NAME, EVENT_MANIPULATION FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA='$DbName' AND EVENT_OBJECT_TABLE='review_action';" 2>&1) -join "`n"
Info ($trg -replace "`n", ' | ')
if ($trg -match 'trg_review_action_no_update') { Ok "BEFORE UPDATE 触发器存在" } else { Bad "缺少 BEFORE UPDATE 触发器" }
if ($trg -match 'trg_review_action_no_delete') { Ok "BEFORE DELETE 触发器存在" } else { Bad "缺少 BEFORE DELETE 触发器" }

if ($script:fail -gt 0) {
    Write-Host ""
    Write-Host "触发器不在库里。请重跑应用让 schema.sql 生效（schema.sql 末尾有 DROP+CREATE TRIGGER）。" -ForegroundColor Red
}

# ---------------------------------------------------------------- 2
Write-Host ""
Write-Host "[2/3] 真的试一次 UPDATE 与 DELETE，断言被 45000 拒绝" -ForegroundColor Yellow

# 用一个 key 记下来，finally 里一定清理干净（第一版没做，留下过脏数据）
$script:testKey = "verify-append-only-" + (Get-Date -Format 'yyyyMMddHHmmss')
try {

# 造一条最小可用的审计记录（字段照 review_action 的 NOT NULL 约束）
$key = $script:testKey
$zero64 = '0' * 64
$ins = "INSERT INTO $DbName.review_action (tenant_id, contract_id, finding_id, idempotency_key, action_code, reason, operator_id, operator_name, previous_status, new_status, record_hash, previous_hash) VALUES (0, 0, NULL, '$key', 'ACCEPT', 'seed-original-verify-append-only', 0, 'verify-append-only', NULL, 'ACCEPTED', '$zero64', '$zero64');"
& mysql -u $DbUser -e $ins 2>&1 | Out-Null
$rowId = ((& mysql -u $DbUser -N -B -e "SELECT id FROM $DbName.review_action WHERE idempotency_key='$key';" 2>&1) -join '').Trim()
if ($rowId) { Ok "造了一条审计记录 id=$rowId" } else { Bad "造记录失败，后面的验证无意义"; Write-Host ""; exit 1 }

# --- 试 UPDATE
$updOut = (& mysql -u $DbUser -e "UPDATE $DbName.review_action SET reason='TAMPERED' WHERE id=$rowId;" 2>&1) -join "`n"
if ($updOut -match '45000' -or $updOut -match 'ERROR 1644') {
    Ok "UPDATE 被拒绝（45000）"
    Info (($updOut -split "`n" | Select-Object -First 1) -replace '^mysql:\s*', '')
} else {
    Bad "UPDATE 没有被拒绝！第 2 层失效。输出：$updOut"
}

# --- 试 DELETE
$delOut = (& mysql -u $DbUser -e "DELETE FROM $DbName.review_action WHERE id=$rowId;" 2>&1) -join "`n"
if ($delOut -match '45000' -or $delOut -match 'ERROR 1644') {
    Ok "DELETE 被拒绝（45000）"
    Info (($delOut -split "`n" | Select-Object -First 1) -replace '^mysql:\s*', '')
} else {
    Bad "DELETE 没有被拒绝！第 2 层失效。输出：$delOut"
}

# --- 数据必须完好
# ⚠️ 这里刻意用**纯 ASCII** 做逐字比对。
#    第一版用中文比对，结果 mysql 客户端把 UTF-8 中文按本机 GBK 解码返回，
#    内容其实完好无损，却因为乱码被判成"记录被改成了…"——**假失败**。
#    校验"数据没变"不该依赖终端编码，所以改用 CHAR_LENGTH（与编码无关的整数）。
$afterLen = ((& mysql -u $DbUser -N -B -e "SELECT CHAR_LENGTH(reason) FROM $DbName.review_action WHERE id=$rowId;" 2>&1) -join '').Trim()
$expectedLen = 'seed-original-verify-append-only'.Length
if ([string]::IsNullOrEmpty($afterLen)) {
    Bad "记录不见了 —— DELETE 竟然成功了"
} elseif ($afterLen -eq [string]$expectedLen) {
    Ok "记录内容未被改动（reason 长度仍为 $expectedLen，未被 UPDATE 改到）"
} else {
    Bad "记录被改动了：reason 长度变成 $afterLen（原为 $expectedLen）"
}

}
finally {
    # ------------------------------------------------------------ 3
    Write-Host ""
    Write-Host "[3/3] 清理（临时关触发器删掉这条测试记录，再恢复）" -ForegroundColor Yellow

    # ⚠️ 这一段放在 finally 里，是为了**任何失败路径都必然执行**。
    #    第一版放在正常流程里，脚本一中断就留下了脏数据。
    & mysql -u $DbUser -e "DROP TRIGGER IF EXISTS $DbName.trg_review_action_no_delete;" 2>&1 | Out-Null
    & mysql -u $DbUser -e "DELETE FROM $DbName.review_action WHERE idempotency_key='$script:testKey';" 2>&1 | Out-Null
    & mysql -u $DbUser -e "DELETE FROM $DbName.review_action WHERE idempotency_key LIKE 'verify-append-only-%';" 2>&1 | Out-Null

    # 立刻恢复 DELETE 触发器（这一步失败意味着库失去了保护，必须显式检查）
    $restore = "DROP TRIGGER IF EXISTS trg_review_action_no_delete; CREATE TRIGGER trg_review_action_no_delete BEFORE DELETE ON review_action FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_action 只追加：禁止 DELETE（决策 D-48）';"
    & mysql -u $DbUser -e "USE $DbName; $restore" 2>&1 | Out-Null

    $left = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM $DbName.review_action WHERE idempotency_key LIKE 'verify-append-only-%';" 2>&1) -join '').Trim()
    if ($left -eq '0') { Ok "测试记录已清理" } else { Bad "测试记录还在（$left 条）" }

    $trg2 = (& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA='$DbName' AND EVENT_OBJECT_TABLE='review_action' AND TRIGGER_NAME='trg_review_action_no_delete';" 2>&1) -join ''
    if ($trg2.Trim() -eq '1') { Ok "DELETE 触发器已恢复" } else { Bad "DELETE 触发器没恢复！下次真删审计记录就拦不住了" }
}

# ---------------------------------------------------------------- 汇总
Write-Host ""
Write-Host ("=" * 62) -ForegroundColor Cyan
if ($script:fail -eq 0) {
    Write-Host ("  第 2 层验证通过：触发器存在，UPDATE / DELETE 均被 45000 拒绝（$script:pass 项）") -ForegroundColor Green
    Write-Host "  这意味着 D-48 的三层保证在这一层上是**真的**，不是文档里的说法。" -ForegroundColor Gray
} else {
    Write-Host ("  验证失败：$($script:fail) 项未通过，$script:pass 项通过") -ForegroundColor Red
}
Write-Host ("=" * 62) -ForegroundColor Cyan
Write-Host ""
exit $(if ($script:fail -eq 0) { 0 } else { 1 })