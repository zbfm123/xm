---
title: T-005 ~ T-008 租户隔离 · 上传幂等 · 查询 · 删除级联
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-005 ~ T-008 租户隔离 · 上传幂等 · 查询 · 删除级联

## 任务边界

- 所属模块：[parse](../modules/parse.md)、[auth](../modules/auth.md)
- 对应需求/验收：**A-01**（跨租户隔离）、A-02（上传侧）、F-02、F-06
- 目标行为：
  - 用户能上传合同，同一文件重复上传不新建记录
  - 合同列表支持分页/关键字/状态筛选，且**只看得到本租户数据**
  - 删除合同会级联清理文件、正文与 **AI 缓存**
- 本任务不做：文本提取与归一化（T-009/T-010）、AI 审查（T-014 起）
- 前置依赖：T-004 ✅
- 涉及的不变式：**I-01**（租户隔离）、I-02 的基础（正文与偏移映射的表结构）、I-05（幂等）

> [!note] 四个任务合并为一张卡
> T-005~T-008 是同一条数据链路（隔离 → 上传 → 查询 → 删除），
> 拆成四张卡只会重复描述同一套上下文。合并记录，但仍逐项验收。

## 验收示例

### 正常路径

- 给定：`staff01` 已登录（租户 1）
- 当：上传 `采购合同.pdf`
- 则：返回 200，状态 `UPLOADED`，文件落盘为 `data/1/<哈希前2位>/<哈希>`

### 异常路径

| 情况 | 期望 |
| --- | --- |
| 同文件重复上传 | 200，**返回同一 id**，`idempotent=true` |
| 不同租户上传同一文件 | 各自独立，互不命中幂等 |
| 对照租户查询本租户合同 | 404 `CONTRACT_NOT_FOUND`（**不是 403**） |
| 对照租户删除本租户合同 | 静默不生效，原合同仍在 |
| 扩展名伪装（文本改名 .pdf） | 400 `MIME_MISMATCH` |
| 不支持的扩展名 | 400 `MIME_MISMATCH` |
| 文件超限 | 400 `FILE_TOO_LARGE`，**且在校验阶段就拒绝，不读内容算哈希** |
| 空文件 | 400 `EMPTY_FILE` |
| 非法状态筛选值 | 400 `INVALID_PARAMETER`（曾错误地返回 500） |
| 无登录上下文 | `TENANT_CONTEXT_MISSING`，**不退化为查全部** |
| 重复删除 | 204（幂等） |

### 明确不该发生的事

- Mapper 的任何一条 SQL 缺少 `tenant_id` → **构建必须失败**（架构测试）
- 删除合同后 AI 缓存仍在 → 重传同一份合同会命中过期结论
- 把 `storagePath`、`fileHash` 返回给前端
- 非法参数被静默忽略（例如未知状态当成"不过滤"）

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `auth/TenantContext.java` | 取当前租户，**取不到就抛异常** |
| `parse/domain/ContractStatus.java` | 状态枚举 + 合法性判断 + 终态定义 |
| `parse/domain/Contract.java` | 合同实体（`fileHash`/`textHash` 分工见类注释） |
| `parse/domain/ContractText.java` | 正文 + 偏移映射（映射待 T-009 填充） |
| `parse/mapper/ContractMapper.java` | 全部 SQL 带 `tenant_id` + `deleted = 0` |
| `parse/mapper/ContractTextMapper.java` | 正文读写与级联删除 |
| `parse/ContractFileStorage.java` | 本地磁盘存储，内容寻址 + 临时文件原子移动 |
| `parse/Digests.java` | SHA-256，文件走流式（不整个读进内存） |
| `parse/ContractService.java` | 上传/查询/删除，租户一律取自上下文 |
| `parse/ContractController.java` | REST 端点，**不接收租户参数** |
| `parse/ParseErrorCode.java` | 9 个错误码，按"调用方该做什么"划分 |
| `aireview/AiResultCache.java` | AI 结果缓存 + **按 textHash 定向清理** |
| `web/GlobalExceptionHandler.java` | 错误码 → HTTP 状态码映射 |
| **`architecture/TenantScopeArchitectureTest.java`** | **扫描全部 Mapper SQL，缺 `tenant_id` 即构建失败** |

## TDD 记录

### 1. 失败测试（RED）

- 先写 `ContractServiceTest#contractsAreIsolatedBetweenTenants`（A-01），此时 `ContractService` 不存在。
- 先写 `TenantScopeArchitectureTest#everyMapperSqlMustBeTenantScoped`，此时尚无 Mapper。

### 2. 最小实现（GREEN）

按上表实现。**刻意没做**：文本提取、AI 审查、异步硬清理任务。

### 3. 必要整理（REFACTOR）

- 路由：`SecurityConfig` 从 `config` 包移到 `security` 包（包名与职责对齐）。
- 拒绝给 `ContractService` 加"仅供测试"的方法：测试改用 `ContractMapper` 直接写 `text_hash`。
  **生产代码里出现 test-only 入口，会诱使后续代码也走它。**

### 4. 验证结果

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| 单元 + 集成测试 | ✅ | `Tests run: 52, Failures: 0, Errors: 0` / BUILD SUCCESS |
| **架构测试真的会失败吗** | ✅ 已验证 | 故意删掉 `ContractTextMapper` 的 `tenant_id` 后，测试报出 `ContractTextMapper 的某条 SQL 缺少 tenant_id` |
| **真实 MySQL 端到端** | ✅ | 17 项 HTTP 验证（下表） |

#### 真实 MySQL 端到端结果

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | 未登录访问合同列表 | 401 `UNAUTHENTICATED` |
| 2 | 上传 PDF | 200，id=1，`UPLOADED` |
| 3 | 同文件重传 | 200，**同一 id**，`idempotent=true` |
| 4 | 对照租户查该合同 | **404** `CONTRACT_NOT_FOUND` |
| 5 | 对照租户列表 | `total=0` |
| 6 | 本租户详情 | 200，**不含 `storagePath`** |
| 7 | 下载原文件 | 200 |
| 8 | 分页 | 正确 |
| 9 | 关键字筛选 | `total=1` |
| 10 | **非法状态筛选** | **修复前 500 → 修复后 400** |
| 11 | 扩展名伪装 | 400 `MIME_MISMATCH` |
| 12 | 不支持的扩展名 | 400 `MIME_MISMATCH` |
| 13 | 删除 | 204 |
| 14 | 重复删除 | 204（幂等） |
| 15 | 删除后查详情 | 404 |
| 16 | 删除后列表 | `total=0` |
| 17 | 删除后重传同一文件 | **新记录**（证明缓存已定向清理，不会复用旧结论） |

### 5. 全量回归

- `mvn test` → 52/52。按纪律只跑一次。

## 实施日志（短期）

- 2026-10-03：架构测试首版扫不到任何 Mapper。原因：`ClassPathScanningCandidateComponentProvider` **默认排除接口**，而 MyBatis Mapper 全是接口。覆写 `isCandidateComponent` 后正常。
  **正因为怕它"扫到 0 个"而假绿，才单独写了 `shouldActuallyFindMappers` 来兜底。**
- 2026-10-03：`TestFiles.minimalPdf` 抛 `UnknownFormatConversionException`。原因：PDF 头 `%PDF` 里的 `%P` 被 `String.formatted()` 当成格式符。改用字符串拼接。
- 2026-10-03：缓存测试失败，`aiResultCache.get()` 返回 null。原因：mock 只 stub 了带 TTL 的 `set(k,v,ttl)`，而 `AiResultCache.put` 调的是两参 `set`，且 `get` 未 stub。
- 2026-10-03：**真实 MySQL 复验发现真 bug**——非法状态筛选返回 500。`IllegalArgumentException` 未显式处理，落到兜底分支。
- 2026-10-03：清理验证产生的测试合同与本地文件，演示数据保持完好。

## 完成结论

- 状态：**已验收**

### 本任务最重要的产出：架构测试

`TenantScopeArchitectureTest` 把"每个查询都要带租户条件"从**靠自觉**变成了**构建时检查**。

这条规矩靠自觉的失效率是 100%——不是今天漏，就是以后加新接口时漏，
而漏掉的后果是跨租户数据泄露。现在忘了加 `tenant_id`，`mvn test` 直接失败并指出是哪个类。

**并且这个测试本身被验证过会失败**（故意破坏后确认报错），
同时用 `shouldActuallyFindMappers` 防止"扫不到类所以假绿"。

### 由真实环境复验暴露的缺陷

非法状态筛选返回 500 而不是 400。服务层测试发现不了——服务层抛异常是对的，错的是映射。
补了 `IllegalArgumentException` 处理器并加了 HTTP 层断言防回归。

> **教训：服务层测试覆盖逻辑，HTTP 层测试覆盖映射。两者不能互相替代。**

### 新增/变更的错误码

`FILE_TOO_LARGE`、`MIME_MISMATCH`、`SIZE_MISMATCH`、`EMPTY_FILE`、`PDF_ENCRYPTED`、`NO_EXTRACTABLE_TEXT`、`PARSE_FAILED`、`CONTRACT_NOT_FOUND`、`STORAGE_UNAVAILABLE`、`INVALID_PARAMETER`

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [parse 模块笔记](../modules/parse.md) | `fileHash` 与 `textHash` 的分工；两段式删除 |
| [auth 模块笔记](../modules/auth.md) | `TenantContext` 的作用与"取不到就失败" |
| [02 架构](../02-architecture.md) | D-17（架构测试锁 I-01）、D-18（404 而非 403）、D-19（删除两段式） |
| [README](../../README.md) | 合同接口清单 |
| [08 面试脚本](../08-interview-and-demo-script.md) | "怎么保证不遗漏租户条件" |

### 面试可讲点

1. **租户隔离你怎么保证不遗漏？** → **不靠自觉，靠构建时检查。** 我写了一个架构测试扫描所有 Mapper 的 SQL，缺 `tenant_id` 就 `mvn test` 失败。而且我验证过这个测试真的会失败——故意删掉一个条件，它精确报出了是哪个类。
2. **为什么另一个租户看到的是 404 而不是 403？** → 403 等于确认"这份合同存在，只是不归你"，接口就变成了合同 id 探测器。**不确认自己不该确认的信息。**
3. **删除合同时为什么要清缓存？** → AI 结果按 `textHash` 缓存。只删库不清缓存，用户重传同一份合同会拿到**与本次无关的过期结论**，而且看起来完全正常。这是本项目 CRUD 里唯一需要动脑的地方。
4. **幂等为什么用文件哈希而不是文件名？** → 同一份文件改名后重传应当命中已有记录。**幂等看内容，不看名字。**
5. **超限校验为什么要在算哈希之前？** → 否则一个 1GB 的非法文件会被完整读一遍算哈希，白费 IO。**校验顺序也是设计。**
6. **为什么有两个哈希（fileHash / textHash）？** → 同样内容的合同可能是不同文件（重新导出后字节不同但文本相同）。**幂等看文件字节，缓存看文本内容。**

### 后续任务

T-009 / T-010（PDF/DOCX 文本提取与保坐标归一化、解析失败显式化）。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(parse): scope contracts by tenant and enforce it with an architecture test` | T-005 |
| `feat(parse): upload contracts with content-hash idempotency` | T-006 |
| `feat(parse): list and search contracts with paging and filters` | T-007 |
| `feat(parse): delete contracts with cascading cleanup and cache eviction` | T-008 |
| `fix(web): map invalid parameters to 400 instead of 500` | 真实环境复验发现 |

%%
本卡已完成验收。稳定的接口、错误码与状态定义已回写 parse 与 auth 模块笔记。
终端的原始输出未长期保存。
%%
