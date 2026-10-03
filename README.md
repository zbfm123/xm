# 合同智能审查平台

> 上传合同 → 提取要素 → 规则校验 → AI 识别风险条款并给出原文证据 → 人工复核 → 报告页。
>
> 核心工程价值不在"接了 AI"，而在**给概率性的 AI 输出加上工程约束**：
> AI 只能产出带原文证据和置信度的**候选**结论，能否采信由确定性规则和人工决定。

---

## 1. 技术栈

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21（Spring Boot 3.3.5） |
| 持久层 | MyBatis-Plus 3.5.7 + MySQL 8 |
| 建表 | `schema.sql` + `data.sql`（**未用 Flyway**，见技术债） |
| 缓存 / 幂等 / 限流 | Redis |
| 文件存储 | 本地目录 `./data/files`（**未用 MinIO**） |
| 鉴权 | Spring Security + JWT |
| 文档解析 | Apache PDFBox 3 + POI 5 |
| AI | `RestClient` 直连 DeepSeek（**未用 Spring AI**） |
| 前端 | Vue 3 + Vite + Element Plus |
| 测试 | JUnit 5 + H2 内存库 + 应用内 Mock 桩（**未用 Testcontainers / WireMock**） |

> 每一项"未用"都是主动决策，理由记录在 [`docs/02-architecture.md`](docs/02-architecture.md#关键决策记录) 的 D-01 ~ D-13。

---

## 2. 环境要求

| 组件 | 版本 | 本机实际状态（2026-10-03 实测） |
| --- | --- | --- |
| JDK | **21** | ⚠️ 原 PATH 指向 JDK 1.8、`JAVA_HOME` 指向 JDK 19，需改为 JDK 21 |
| Maven | 3.9+ | 3.9.9 ✅ |
| MySQL | 8.0+ | Windows 服务 `MySQL80`，监听 3306 ✅ |
| Redis | 5+ | Windows 服务 `Redis`，监听 6379 ✅（本机为 3.2.100，见下方注意事项） |
| Node.js | 20+ | v24.18.0 ✅（仅前端需要） |

**本项目不需要 Docker。**

> [!note] 关于本机 Redis 3.2.100
> 该版本较老，缺少部分新命令。本项目只用 `GET/SET/EXPIRE/DEL` 与字符串自增，
> 均在 3.2 支持范围内。若用到 `Stream` 等新特性需升级。

---

## 3. 首次启动步骤

> [!tip] 一键脚本已经帮你做完 3.1~3.3
> 直接运行下面这条，脚本会自动校验 JDK、设置凭据、检查中间件、启动应用：
> ```powershell
> .\run-dev.ps1
> ```
> 下面的手工步骤仅在你需要单独排查时使用。

### 3.1 创建数据库

本机已创建（2026-10-03），如需在其他机器重建：

```sql
CREATE DATABASE IF NOT EXISTS contract_review
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;
```

建表与初始数据由 Spring Boot 在启动时自动执行（`application-dev.yml` 中 `spring.sql.init.mode=always`），**不需要手工执行 SQL 文件**。

### 3.2 数据库与 Redis 凭据

凭据**不写进配置文件**，有两种提供方式，任选其一：

**方式一（推荐）：本地凭据文件**

```powershell
Copy-Item src\main\resources\application-local.yml.example src\main\resources\application-local.yml
# 然后编辑 application-local.yml，填入你的 MySQL 与 Redis 密码
```

`application-local.yml` 已被 `.gitignore` 排除，**不会进入仓库**。

**方式二：环境变量**

```powershell
$env:DB_USER        = "root"
$env:DB_PASSWORD    = "<你的密码>"
$env:REDIS_PASSWORD = "<你的密码>"
```

> [!warning] 为什么不把密码写死在配置里
> 仓库是公开的。把密码写在 `application-dev.yml` 里虽然"跑起来方便"，
> 但会让面试官看到一个把口令提交进版本库的项目——**这是减分项，不是小事**。
> 因此 `application-dev.yml` 只保留 `${DB_PASSWORD:}` 这样的占位，真实值一律外部注入。

### 3.3 配置 AI 通道（可选）

```powershell
# 默认 false：走内置 Mock 桩，零额度消耗，开发/测试/断网演示都能跑
$env:AI_ENABLED = "false"

# 只在需要真实调用时打开
$env:AI_ENABLED        = "true"
$env:DEEPSEEK_API_KEY  = "sk-你的key"
```

> [!warning] 额度保护
> 接口额度仅 15 元，**真正的风险不是额度不够，而是调试时的循环调用**：
> 缓存没生效 → 跑一次集成测试 20 次调用 → 跑十遍 200 次。
>
> 因此代码里有三道限制：
> 1. **默认走 Mock 桩**（`AI_ENABLED=false`），零额度消耗，断网也能演示
> 2. **单份合同调用次数上限**，超过直接失败而不是继续调
> 3. **日预算上限**，把 token 换算成估算金额累计
>
> 开发与测试请保持 `AI_ENABLED=false`。**Mock 模式下不限额**——
> 桩调用不花钱，限制它只会妨碍开发。

#### AI 通道切换（三种）

| `AI_CLIENT_MODE` | `AI_ENABLED` | 注入的客户端 | 用途 |
| --- | --- | --- | --- |
| `auto`（默认） | `false` | `MockAiClient` | 开发、测试、断网演示、零消耗 |
| `auto` | `true` | `DeepSeekClient` | 真实调用（需 `DEEPSEEK_API_KEY`） |
| **`unavailable`** | 任意 | `UnavailableAiClient` | **确定性地演示降级路径** |

`/api/health` 的 `aiEnabled` 字段会如实反映通道。

> [!note] 为什么需要 `unavailable` 这个通道
> 降级（不变式 I-04）是三个「绝不砍」项之一，但它**必须能确定性地演示**。
> 而 Mock 桩设计成"永远可成功"，**正好掩盖了降级路径**；
> 不配 Key 会回退到 Mock；拔网线则不可复现。
>
> 所以提供了一个"就是不可用"的通道。演示降级：
> ```powershell
> $env:AI_ENABLED="true"; $env:AI_CLIENT_MODE="unavailable"; .\run-dev.ps1
> ```
> 然后上传合同 → 启动审查任务 → 你会看到任务停在 `AI_UNAVAILABLE`，
> **而不是** `AWAITING_REVIEW`，且规则结论完整保留。

#### AI 调用失败的状态码

| 错误码 | HTTP | `degradable` | `retryable` |
| --- | --- | --- | --- |
| `AI_UNAVAILABLE` | 503 | ✅ | ❌ |
| `BUDGET_EXCEEDED` / `CALL_LIMIT_EXCEEDED` | 429 | ✅ | ❌ |
| `AI_TIMEOUT` / `AI_RATE_LIMITED` / `AI_SERVER_ERROR` | 502 | ❌ | ✅ |
| `SCHEMA_INVALID` | 502 | ❌ | ❌ |

响应里带 `degradable` 与 `retryable` 两个布尔，
**让客户端不必自己推断该怎么办**。有一条测试穷举所有错误码，
确保**没有任何一类落到兜底的 500**——否则调用方无法区分
"AI 不可用（可降级继续）"与"服务端崩了"。

### 3.4 启动

```powershell
# 确认 JDK 21 生效
java -version

# 启动（dev profile 会连本机 MySQL 并自动建表）
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### 3.5 自检

```powershell
curl http://localhost:8080/api/health
```

期望返回：

```json
{
  "status": "UP",
  "application": "contract-review",
  "aiEnabled": false,
  "aiModel": "deepseek-chat",
  "promptVersion": "v1"
}
```

`aiEnabled` 会如实反映当前走的是真实调用还是 Mock 桩——**演示前先看这一项**，
避免出现"以为在真调模型，其实在读缓存"的误判。

### 3.6 演示账号

初始数据（`db/data.sql`）内置三个**虚构**账号，口令统一为 **`Demo@2026`**：

| 账号 | 角色 | 租户 | 权限 |
| --- | --- | --- | --- |
| `staff01` | `LEGAL_STAFF` | 演示租户 | 可复核普通条目，**无权终审低置信度结论** |
| `lead01` | `LEGAL_LEAD` | 演示租户 | 可终审低置信度条目（AI 误判的兜底人） |
| `other01` | `LEGAL_STAFF` | 对照租户 | 用于验证跨租户隔离（A-01） |

> [!note] 为什么把明文口令写在这里
> 否则没人知道哈希对应什么口令，演示时登录不上还得反过来猜。
> 这是本地演示数据，不是真实凭据。真实环境的口令一律走部署配置。

### 3.7 认证接口自测

```powershell
# 登录
curl.exe -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"staff01\",\"password\":\"Demo@2026\"}"

# 用返回的 token 访问当前用户（把 <token> 换成实际值）
curl.exe -s http://localhost:8080/api/auth/me -H "Authorization: Bearer <token>"
```

| 端点 | 鉴权 | 说明 |
| --- | --- | --- |
| `POST /api/auth/login` | 匿名 | 成功返回 JWT；失败 401 `BAD_CREDENTIALS`；连续失败 5 次锁定 423 `ACCOUNT_LOCKED` |
| `POST /api/auth/logout` | 匿名（**幂等**） | 令牌写入黑名单；重复登出仍返回 204 |
| `GET /api/auth/me` | 需登录 | 返回当前用户，用于刷新页面后恢复状态 |
| `GET /api/health` | 匿名 | 自检，含当前 AI 通道状态 |

### 3.8 合同接口

全部需要登录，且**租户范围自动取自登录上下文，接口不接收租户参数**。

| 端点 | 说明 |
| --- | --- |
| `POST /api/contracts` | 上传（`multipart/form-data`，字段 `file` + 可选 `title`）。同一文件重复上传返回同一 id 并置 `idempotent=true` |
| `POST /api/contracts/{id}/parse` | 解析文本。成功返回 `success=true` + `textHash`；失败也返回 **200** + `success=false` + `errorCode`（加密 / 扫描件 / 损坏）——这是业务结果，不是请求错误 |
| `GET /api/contracts/{id}/text` | 读取归一化正文，供人工查看与核对证据位置 |
| `GET /api/contracts` | 分页列表。参数：`keyword`（匹配标题或文件名）、`status`、`page`（从 0 开始）、`size`（1~100） |
| `GET /api/contracts/{id}` | 详情。**不返回存储路径与哈希** |
| `GET /api/contracts/{id}/file` | 下载原始文件 |
| `DELETE /api/contracts/{id}` | 删除（软删除 + 级联清理文件、正文、规则结论、该文本哈希的 AI 缓存）。幂等 |
| `POST /api/contracts/{id}/rule-check` | 执行规则校验（**不联网**）。返回三类计数：命中 / 通过 / **无法判定** |
| `GET /api/contracts/{id}/findings` | 查询规则结论。`?onlyHits=true` 只看命中项 |

### 规则引擎：结果三分

这是本项目最重要的设计之一。规则结果不是布尔，而是三态：

| 结果 | 含义 | 用户可见 |
| --- | --- | --- |
| `HIT` | 确定存在该问题 | 问题条目 |
| `PASS` | 确定不存在该问题 | 不显示 |
| **`UNDETERMINED`** | **输入不足，无法判定** | **待人工确认** |

**为什么不能只有布尔**：如果把"要素抽不到"当成"通过"，系统就会在信息不足时
输出"合同合规"——**这比报错危险得多**。因此 `UNDETERMINED` 在引擎统计、
数据库列、API 响应里都是独立的一态，且有专门的测试断言它不会被折叠成 `PASS`。

当前四条规则（全部为确定性判断，**不调用任何模型**）：

| 规则编码 | 判断内容 |
| --- | --- |
| `R-AMOUNT-MISMATCH` | 金额大小写是否一致（含**中文大写金额解析**，如"壹拾贰万捌仟元整"= 128000） |
| `R-DATE-ORDER` | 签署日 ≤ 生效日 ≤ 到期日 |
| `R-CLAUSE-MISSING` | 争议解决 / 付款 / 违约三类条款是否存在 |
| `R-PARTY-INCONSISTENT` | 抽取到的甲乙方名称能否在正文中检索到 |

> [!note] 规则的已知局限（面试时应主动说明）
> - 严重级别（HIGH/MEDIUM/LOW）目前**凭业务常识设定，没有真实法务依据**。
>   真实系统中级别应当来自业务方的风险清单。
> - `R-CLAUSE-MISSING` 只判断关键字是否出现，**不判断条款内容是否有效**。
> - 中文大写金额只支持到"元"；遇到角分返回"无法判定"而不是猜一个值。
> - 规则集是硬编码的，未做可视化规则编排。

### 要素抽取与 AI 风险审查

| 端点 | 说明 |
| --- | --- |
| `POST /api/contracts/{id}/extract` | 要素抽取（走 AI 通道，默认 Mock）。返回里单列 `mismatch` = 引文无法定位的字段数 |
| `GET /api/contracts/{id}/elements` | 查询要素，含引文、原文区间、置信度、匹配级别、状态 |
| `POST /api/contracts/{id}/ai-review` | AI 风险审查。返回 `pending` / `lowConfidence` / `mismatch` / `reportable` |
| `GET /api/contracts/{id}/ai-findings` | 查询 AI 候选结论。`?reportableOnly=true` 只返回**证据已定位、可进报告正文**的条目 |

`mismatch` 是本模块最重要的健康指标：**它偏高说明模型在改写引文，或提示词需要调整。**

#### 四道闸门：AI 输出如何变成可裁决的结论

```
模型输出
  ├─ 1. schema 校验   结构非法 / riskType 越界 / confidence 越界 → 整体丢弃
  ├─ 2. 证据对齐      引文必须在原文定位到，否则标 EVIDENCE_MISMATCH，不进报告正文
  ├─ 3. 置信度裁决    模型自评 × 匹配级别权重 × 引文长度惩罚；低于阈值强制人工
  └─ 4. 状态机        所有条目初始都是候选态，没有"已生效"这个状态
```

> [!important] 核心思路
> **不是让模型更准，而是让它的错误变得可检测。**
>
> 引文定位不到的结论会被拦下来并转人工。这条路径可以现场演示——
> 用 `sparse` 模板（它自带触发幻觉的标记）。

#### 示例合同模板（`/api/debug/sample-contract.pdf?template=`）

| 模板 | 用途 | 预期结果 |
| --- | --- | --- |
| `well-formed` | 要素齐全、无风险条款 | 规则 4 条全 `PASS`；AI 审查 0 条候选 |
| `sparse` | 无要素、且自带触发幻觉的标记 | 规则 3 条 `UNDETERMINED`；**AI 审查出现 `EVIDENCE_MISMATCH`** |
| `risky` | 含四类典型风险条款 | AI 审查产出 4 条 `PENDING` 候选，全部证据已定位 |

### 审查任务状态机

| 端点 | 说明 |
| --- | --- |
| `POST /api/contracts/{id}/review-tasks` | 启动审查任务。body：`{idempotencyKey}`，**幂等**——重复提交返回同一 `taskId` 且不重跑 |
| `GET /api/review-tasks/{taskId}` | 查任务状态，响应含 `allowedNextStatuses` |
| `GET /api/contracts/{id}/review-tasks` | 该合同的全部任务（保留多次审查历史） |
| `POST /api/review-tasks/{taskId}/refresh` | 刷新进度（按已复核条数决定是否完成） |
| `POST /api/review-tasks/{taskId}/proceed` | **降级后继续进入人工复核** |

```
PENDING ──→ IN_PROGRESS ──→ AWAITING_REVIEW ──→ COMPLETED
                 │                  ↑
                 └──→ AI_UNAVAILABLE ┘
```

> [!important] 两条关键设计
> **1. `AI_UNAVAILABLE` 不是终态。** 它有一条边通向 `AWAITING_REVIEW`——
> 降级后规则结论与要素抽取都还在，人工照常工作。
> 设成失败终态就等于"AI 挂了整份审查就废了"，直接违反 I-04。
>
> **2. `CANCELLED` 不能从终态进入。** "已完成"不能变成可撤销。
>
> 另外，任务表与合同状态是两件事：
> `contract.status` 是**数据**状态（已解析/已校验），`task.status` 是**流程**状态。

### 人工复核与只追加审计

| 端点 | 说明 |
| --- | --- |
| `POST /api/contracts/{id}/reviews` | 记录复核动作。body：`{findingId, action, reason, idempotencyKey}`，**幂等** |
| `GET /api/contracts/{id}/reviews` | 复核历史（按时间正序） |
| `GET /api/contracts/{id}/reviews/verify` | **哈希链完整性校验**：报告是否被改动过、断点在哪一条 |

复核动作：`ACCEPT`（采纳进正文）/ `REJECT`（驳回）/ `ESCALATE`（升级主管）/
`NEED_INFO`（退回补充）/ `CONFIRM_NO_RISK`（确认整份合同无风险，合同级动作）。

#### 「只追加」由三层保证

| 层 | 手段 | 防住什么 |
| --- | --- | --- |
| 接口 | `ReviewActionMapper` **只有 insert 与 find** | 应用代码想改也找不到方法（有反射测试强制） |
| 数据库 | `BEFORE UPDATE` / `BEFORE DELETE` **触发器** | 手工 SQL、运维脚本 |
| 密码学 | **哈希链** | 有 root 权限的人——改动可被检测 |

> [!warning] 哈希链的边界（面试时请主动说明）
> 它能防**篡改**，**不能防抵赖**。知道算法与字段顺序的人可以重算整条链，
> 伪造一个自洽的历史。真正的不可抵赖需要**外部时间戳或数字签名**。
>
> 另外：**审计记录不随合同删除而消失**。
> 合同被删了，但"谁在什么时候做了什么判断"这个事实不因此消失。
> 这是刻意的设计，有测试锁定，请不要"顺手"给它加级联清理。

### 调试端点（仅 dev / test 环境）

| 端点 | 说明 |
| --- | --- |
| `GET /api/debug/sample-contract.pdf?template=well-formed` | 生成要素齐全的虚构合同，四条规则都会给出确定结论 |
| `GET /api/debug/sample-contract.pdf?template=sparse` | 生成只有正文没有要素的合同，用于观察"无法判定" |
| `GET /api/debug/align/{contractId}?quote=&context=` | **证据对齐调试**：给一句引文，看它落在原文哪里、用了哪级匹配、相似度多少、为什么失败 |
| `GET /api/debug/align/{contractId}/info` | 查看归一化规模与映射一致性 |

这些端点由 `@Profile({"dev","test","default"})` 控制，**不会出现在生产环境**。
调试台页面上有对应入口，不需要手工拼 URL。

#### 证据对齐调试示例

```powershell
# 逐字相同 → EXACT，权重 1.0
curl.exe "http://localhost:8080/api/debug/align/1?quote=争议解决：提交北京仲裁委员会。"

# 改一个字 → FUZZY，相似度下降，权重 0.7
curl.exe "http://localhost:8080/api/debug/align/1?quote=争议解决：提交上海仲裁委员会。"

# 原文不存在的内容 → 未命中，位置为 null
curl.exe "http://localhost:8080/api/debug/align/1?quote=乙方承担一切损失且责任无上限"
```

#### 两个模板的预期结果对照

| 模板 | 内容 | 规则结果 |
| --- | --- | --- |
| `well-formed` | 甲乙方、金额（含大写）、三个日期、三类条款齐全 | 命中 0 / 通过 **4** / 无法判定 0 |
| `sparse` | 只有一段说明文字，没有任何要素 | 命中 0 / 通过 1 / **无法判定 3** |

`sparse` 的三条"无法判定"分别来自金额、日期、主体规则，并会写明缺哪个字段：

```
[UNDETERMINED] R-AMOUNT-MISMATCH     缺少金额要素：AMOUNT(UNKNOWN)、AMOUNT_IN_WORDS(UNKNOWN)
[UNDETERMINED] R-DATE-ORDER          可比较的日期不足两个：SIGN_DATE(UNKNOWN)、...
[UNDETERMINED] R-PARTY-INCONSISTENT  甲乙方名称均未抽到：PARTY_A(UNKNOWN)、PARTY_B(UNKNOWN)
[PASS        ] R-CLAUSE-MISSING      争议解决、付款、违约三类条款均可检索到
```

> [!tip] 为什么值得对比这两个模板
> 如果把"要素抽不到"当成"规则通过"，`sparse` 就会显示 4 条通过——**系统在什么都不知道的时候
> 告诉用户"合同没问题"**。这两个模板放在一起看，才能理解为什么规则结果必须是三态。

上传校验顺序（顺序本身是设计）：**大小 → 扩展名 → 算哈希查幂等 → 魔数校验**。
超限文件在校验阶段就被拒绝，不会被完整读一遍算哈希。

文本处理要点：

- PDF 按页提取，**保留分页边界**供页眉页脚识别
- DOCX 提取段落**与表格**（合同金额常在表格中，只读段落会静默丢掉）
- 归一化**保坐标**：统一换行 → 全角数字字母转半角（**不转中文标点**）→ 去重复页眉页脚 → 折叠空白，
  每步维护「归一化下标 → 原文下标」映射并落库为 `offset_map`
- 页眉页脚清理**宁漏删不误删**：单页不清理、≥30 字不清理

```powershell
# 上传（需要先登录拿 token）
curl.exe -s -X POST http://localhost:8080/api/contracts `
  -H "Authorization: Bearer <token>" `
  -F "file=@D:\path\to\合同.pdf" -F "title=采购合同"

# 解析文本
curl.exe -s -X POST http://localhost:8080/api/contracts/1/parse -H "Authorization: Bearer <token>"

# 查看归一化正文
curl.exe -s http://localhost:8080/api/contracts/1/text -H "Authorization: Bearer <token>"

# 列表（关键字 + 状态筛选）
curl.exe -s "http://localhost:8080/api/contracts?keyword=采购&status=PARSED&page=0&size=20" `
  -H "Authorization: Bearer <token>"
```

### 3.9 浏览器里看效果（自带调试台）

服务起来后，直接打开：

```
http://localhost:8080
```

这是一个**单文件调试台**（`src/main/resources/static/index.html`，零构建工具），
可以在浏览器里点完整个流程：登录 → 上传 → 列表 → 解析 → 查看正文 → 删除。

> [!note] 它是什么、不是什么
> **是**：开发与演示用的调试台，用来验证后端能力、排错、面试演示。
> **不是**：交付级前端。正式的 Vue 3 前端排在 Day 5，届时本页保留作为排错工具。
>
> 页面本身是公开静态资源（否则打不开），但**所有数据请求都要 JWT**，
> 所以放行它不降低安全性——这一点有测试锁住（`DebugPageTest`）。

页面上演示口令已写明：`staff01` / `Demo@2026`（租户 1）、
`other01` / `Demo@2026`（租户 2，用于对比租户隔离）。

### 3.10 跑测试

```powershell
mvn test
```

测试使用 H2 内存库与 Mock 桩，**不依赖本地 MySQL / Redis，也不消耗 AI 额度**。

当前覆盖：**99 个用例**，包含：

- **`TenantScopeArchitectureTest`** —— 扫描所有 Mapper 的 SQL，缺 `tenant_id` 即构建失败，锁死多租户隔离
- `ContractTextExtractorTest` —— 用**真实 PDF / DOCX**（含加密、表格）验证提取
- `TextNormalizerTest` —— 重点断言**坐标映射可回查原文**，而不只是"文本被洗净了"
- `DebugPageTest` —— 起真实内嵌容器验证调试台可访问、中文正确、接口仍受保护

---

## 4. 项目结构

```
contract-review-platform/
├── docs/                      项目知识库（先读 docs/README.md）
│   ├── 00-project-index.md    项目索引与模块导航
│   ├── 01-requirements-and-scope.md
│   ├── 02-architecture.md     模块边界、不变式、决策记录
│   ├── 04-tasks-and-acceptance.md   5 天冲刺任务看板
│   ├── 06-ai-session-prompts.md     与 AI 协作的提问模板
│   ├── 08-interview-and-demo-script.md  演示脚本与面试问答
│   ├── modules/               各模块设计笔记
│   └── tasks/                 单任务卡
├── src/main/java/com/demo/contract/
│   ├── auth/        登录、JWT、租户上下文
│   ├── parse/       上传、文本提取、保坐标归一化
│   ├── extract/     要素抽取 + 证据对齐算法
│   ├── rule/        确定性规则引擎（不触网）
│   ├── aireview/    AI 风险审查（只产出候选）
│   ├── workflow/    状态机、幂等、人工复核、降级
│   ├── config/      配置类
│   └── health/      自检端点
├── src/main/resources/
│   ├── application.yml / application-dev.yml
│   └── db/schema.sql / data.sql
└── src/test/        H2 + Mock 桩测试
```

---

## 5. 已知限制与技术债

> 主动列出来，不是遗忘。每条都有"为什么现在不还"的理由。

| 项 | 说明 | 为什么现在不还 |
| --- | --- | --- |
| **无迁移框架** | 用 `schema.sql`，改表靠手工 ALTER，且要同步维护 H2 版脚本 | 5 天期限内不值得学 Flyway；给一周第一件补这个 |
| **文件存本地磁盘** | 单机可用，多副本部署需替换实现 | 已按存储接口抽象，替换成本集中在一处 |
| **AI 无评测集** | 准确率无量化数据，因此结论只能是"候选" | 没有评测能力时，把 AI 定位成建议而非结论是更诚实的架构选择 |
| **前端未用 TypeScript** | 类型靠接口文档约束 | 5 天内类型对齐成本高于收益 |
| **未做** | 统计看板、批量审查、报告文件导出、向量检索、OCR、电子签章 | 范围决策，不是能力所限 |
| **权限仅两级** | 租户 + 角色 | 演示场景够用 |

---

## 6. 与 AI 协作的约定

本项目遵循「规格先行、测试驱动、小步提交」。开发时请遵守：

1. **规格不存在，不动工。** 新功能先写进 `docs/01` 与 `docs/04`，再开对话。
2. **一个对话只做一个任务。** 让 AI 先读 `docs/00` → `docs/02` → 对应模块笔记 → 任务卡。
3. **先写会失败的测试**（核心逻辑必须），再写最小实现。
4. **错误分支必须有明确错误码**，禁止用宽泛 `catch` 吞掉根因或用默认值兜底。
5. **复核记录只追加**，不覆盖历史。

可直接复制的提问模板见 [`docs/06-ai-session-prompts.md`](docs/06-ai-session-prompts.md)。
