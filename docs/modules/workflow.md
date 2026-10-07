---
title: 审查工作流 workflow
aliases:
  - workflow
  - 工作流
  - 状态机
tags:
  - 项目
  - 模块
  - 工作流
status: 已完成
---

# 审查工作流 workflow

## 0. 一句话定位

用状态机串起各模块，管住幂等、分派、人工复核与降级，并保证审计痕迹只追加不覆盖。

**不变量**：I-04（AI 不可用可降级）、I-05（幂等）、I-06（审计只追加）。

## 1. 职责与边界

### 模块职责

审查任务的生命周期管理、状态迁移合法性校验、幂等键、人工复核（采信/驳回/改写理由）、低置信度升级、AI 不可用降级、批量任务串行调度。

### 不负责什么

- 不实现任何审查逻辑（不解析、不抽取、不调模型）→ 只**编排**
- 不修改 [ai-review](ai-review.md) 或 [rule](rule.md) 产出的原始结论 → 人工意见作为**新记录**追加
- 不做统计呈现 → 交给 report

### 上下游关系

| 方向 | 模块/系统 | 交换内容 | 约定 |
| --- | --- | --- | --- |
| 输入 | [parse](parse.md) / [extract](extract.md) / [rule](rule.md) / [ai-review](ai-review.md) | 各自完成事件 | 事件驱动推进，不反向调用 |
| 输入 | 前端 | 复核操作（采信/驳回 + 理由） | 必须携带操作人上下文 |
| 输出 | report | 已生效结论集合 | 只暴露生效结论，不暴露原始候选 |

### 允许的依赖

- 允许调用：全部审查模块（这是唯一允许"知道所有人"的模块）、MySQL、Redis
- 禁止：任何模块反向依赖 workflow（否则形成环）

## 2. 功能清单

| 编号 | 功能 | 输入 | 输出 | 关联需求 |
| --- | --- | --- | --- | --- |
| M-W01 | 状态机与合法迁移校验 | 事件 | 新状态 | F-06 |
| M-W02 | 幂等键与重复提交去重 | `fileHash` / `textHash` | 已有任务 | F-06 / I-05 |
| M-W03 | 人工复核与审计追加 | 复核操作 | `ReviewAction` | F-07 / A-09 |
| M-W04 | 低置信度升级复核 | 置信度 + 角色 | 分派给主管 | F-06 |
| M-W05 | AI 不可用降级 | `AI_UNAVAILABLE` | 跳过 AI，继续流转 | A-08 / I-04 |
| M-W06 | 批量串行任务 | 合同 id 列表 | 串行执行 + 进度 | F-10（P2） |

## 3. 核心流程

### 状态机（⚠️ 本节曾把两个独立的状态机混成一个）

本项目有**两个互不相干**的状态机，必须分开讲：

#### ① 合同状态（`ContractStatus`，parse 模块）

~~~mermaid
stateDiagram-v2
    [*] --> UPLOADED
    UPLOADED --> PARSING
    PARSING --> PARSED
    PARSING --> PARSE_FAILED
    PARSED --> RULE_CHECKED
    RULE_CHECKED --> COMPLETED
    PARSE_FAILED --> [*]
    COMPLETED --> [*]
~~~

取值：`UPLOADED` / `PARSING` / `PARSE_FAILED` / `PARSED` / `RULE_CHECKED` / `COMPLETED` / `DELETED`。
终态：`PARSE_FAILED` / `COMPLETED` / `DELETED`。

#### ② 审查任务状态（`ReviewTaskStatus`，**本模块的状态机**）

~~~mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> IN_PROGRESS
    PENDING --> CANCELLED
    IN_PROGRESS --> AWAITING_REVIEW
    IN_PROGRESS --> AI_UNAVAILABLE
    IN_PROGRESS --> CANCELLED
    AI_UNAVAILABLE --> AWAITING_REVIEW
    AI_UNAVAILABLE --> CANCELLED
    AWAITING_REVIEW --> COMPLETED
    AWAITING_REVIEW --> CANCELLED
    COMPLETED --> [*]
    CANCELLED --> [*]
~~~

取值：`PENDING` / `IN_PROGRESS` / `AI_UNAVAILABLE` / `AWAITING_REVIEW` / `COMPLETED` / `CANCELLED`。
终态：`COMPLETED` / `CANCELLED`。

> ⚠️ **本节曾经错在哪里**：原图把
> `UPLOADED/PARSING/PARSED/RULE_CHECKED/EXTRACTING/EXTRACTED/AI_REVIEWING/REVIEWING`
> 画成一条流水线 —— 前四个是 **合同**的状态，
> 而 `EXTRACTING/EXTRACTED/AI_REVIEWING/REVIEWING` **两边都不属于**。
> 两个状态机被画成一个，读起来像“上传一个文件会经过 9 个状态”——
> 而真实的 6×6 = 36 个状态对、合法 14 条，测试是按 `ReviewTaskStatus` 穷举的。

> [!note] 注意 `AI_UNAVAILABLE → AWAITING_REVIEW` 是**需要显式推进**的
> AI 不可用**不是终态**，这一点原意是对的——否则就等于
> “AI 挂了整个审查就废了”，直接违反 I-04。
>
> 但原文写的是“`AI_UNAVAILABLE → REVIEWING`（自动推进）”——
> **实际不自动**：必须调用显式的 `proceed`（决策 D-57）。
> 这是刻意的：“AI 不可用”后究竟是“等修复后重跑”还是“带着不完整的结论继续走”，
> 是一个**要人做的决定**，不能由系统替他默认选一个。

### 状态名的两套取值（别混）

| 用在哪 | 枚举 | 值 |
| --- | --- | --- |
| 合同本身 | `ContractStatus` | UPLOADED / PARSING / PARSE_FAILED / PARSED / RULE_CHECKED / COMPLETED / DELETED |
| 审查任务 | `ReviewTaskStatus` | PENDING / IN_PROGRESS / AI_UNAVAILABLE / AWAITING_REVIEW / COMPLETED / CANCELLED |
| AI 结论 | `ai_finding.status`（字符串）| PENDING / LOW_CONFIDENCE / EVIDENCE_MISMATCH / EVIDENCE_AMBIGUOUS / ACCEPTED / REJECTED / ESCALATED / NEED_INFO / CONFIRMED_NO_RISK |

### 流程：人工复核

1. 前端提交复核操作：`ACCEPT` / `REJECT`，必须带 `reason`。
2. 校验权限：低置信度条目仅 `LEGAL_LEAD` 可终审；`DEMO_READONLY` 一律拒绝。
3. 校验当前状态：条目已是终态（`ACCEPTED`/`REJECTED`）时**拒绝重复复核**（除非显式"撤回"，本期不做）。
4. **追加**一条 `review_action` 记录（insert only），包含操作人、时间、动作、理由，以及**被复核的原始 AI 建议快照**。
5. 更新条目的当前裁决状态（这是独立的当前态字段，不覆盖历史记录）。
6. 全部条目处理完毕 → 合同状态 `COMPLETED`。

> [!important] "只追加"到底指什么
> `review_action` 表**只 insert，永不 update/delete**。
> 原始 `ai_finding` 行也**永不被修改**——人工的采信/驳回写在新记录里。
> 这样任何时刻都能回答："这条 AI 原话是什么？谁在什么时候以什么理由否掉了它？"

### 流程：批量审查（P2）

1. 提交一批合同 → 生成一个批量任务，按提交顺序入队。
2. **串行**取任务执行（单线程消费），不并发。
3. 每个合同执行前检查暂停标志；暂停后不再发起**新的**外部调用，但当前这一份允许跑完（避免产生半份结果）。
4. 记录每份进度与失败原因，单份失败不中断整批。
5. 支持恢复：从第一个未完成项继续。

> [!note] 为什么刻意不并发
> 外部平台有速率限制，并发只会把限流错误放大，并且让"哪个命令属于哪个任务"变得难以追踪
> （对应工作流原始红线：**批量任务必须保持串行、节流和命令归属**）。

### 异常与降级路径

| 情况 | 判定方式 | 系统行为 | 是否转人工 |
| --- | --- | --- | --- |
| 非法状态迁移 | 目标状态不在 `allowedTargets()` 里 | 抛 `IllegalArgumentException`（**没有 `ILLEGAL_TRANSITION` 这个错误码**），状态不变 | 否（是 bug）|
| 重复提交同一请求 | 同一 `idempotencyKey` 命中 | 返回已有任务，不新建 | 否 |
| AI 不可用 | 任务状态为 `AI_UNAVAILABLE` | 等待**显式调用 `POST /api/review-tasks/{id}/proceed`** 才转 `AWAITING_REVIEW`（不自动推进，见上文）；规则结论照常保留 | 是 |
| 低置信度条目 | `confidence` < 阈值 | 强制升级，仅主管可终审 | 是 |
| 复核权限不足 | 角色校验失败 | 403 `INSUFFICIENT_ROLE` | 否 |
| 重复复核同一条目 | 条目已是终态 | 409 `ALREADY_REVIEWED` | 否 |
| ~~批量任务单份失败~~ | —— **批量审查本期未实现**（主动砍掉，见 07 变更记录）| —— | —— |

## 4. 数据与接口

### 数据结构

| 字段/对象 | 类型 | 含义 | 必填 | 来源 | 去向 |
| --- | --- | --- | --- | --- | --- |
| `ReviewTask.id` | Long | 审查任务 id | 是 | workflow | 全链路 |
| `ReviewTask.status` | Enum | 见状态机 | 是 | workflow | 前端、report |
| `ReviewTask.idempotencyKey` | String | **调用方传入**的幂等键（必填）| 是 | 调用方 | 幂等（`uk_review_task_idem` 唯一索引）|
| ~~`FindingDecision`~~ | —— | **这张表不存在**。条目的当前裁决态就在 `ai_finding.status` 字段上 | —— | 复核 | report |
| `ReviewAction` | 表 | **只追加**的审计记录 | 是 | 复核 | report、审计 |
| ~~`ReviewAction.findingSnapshot`~~ | —— | **没有这个列**。`review_action` 只记录动作与状态变化（previous/new_status + reason），不存 AI 建议快照 | —— | —— | —— |
| `ReviewAction.operatorId/createdAt` | — | 操作人、时间 | 是 | auth 上下文 | 审计 |

### 接口/事件

| 名称 | 调用方 | 输入 | 成功输出 | 失败输出 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `POST /api/contracts/{id}/review-tasks` | 前端 | contractId | `{taskId, status}` | 409（已存在，返回已有 id） | 幂等 |
| `GET /api/review-tasks/{taskId}` | 前端 | taskId | 任务状态 + 条目列表 | 404 | 强制 tenantId |
| `POST /api/contracts/{id}/reviews` | 前端 | action, reason, findingId, idempotencyKey | 201/记录 | 403 / 409 | 只追加（实际路径）|
| ~~`POST /api/review-tasks/batch`~~ | —— | **批量审查未实现** | —— | —— | P2，本期砍掉 |
| ~~`POST /api/batch-tasks/{batchId}/pause`~~ | —— | **未实现** | —— | —— | P2，本期砍掉 |
| 领域事件 | —— | **本项目不用事件驱动**：模块间是直接方法调用（模块化单体，见 D-01），状态推进由调用方显式触发 | —— |

## 5. 状态、错误码与排查

| 错误码 | 触发条件 | 用户可见结果 | 系统行为 | 优先排查位置 | 是否可重试 |
| --- | --- | --- | --- | --- | --- |
| ~~`ILLEGAL_TRANSITION`~~ | 状态机不允许的迁移 | **该错误码不存在**：`canMoveTo` 返回 false，异常情况抛 `IllegalArgumentException` | 状态不变 | 状态机定义 | 否 |
| `ALREADY_REVIEWED` | 重复复核终态条目 | "该条已复核" | 拒绝写入 | 裁决状态校验 | 否 |
| `INSUFFICIENT_ROLE` | 角色不足 | "无权终审该条目" | 403 | 角色校验 | 否 |
| `AI_UNAVAILABLE` | AI 依赖不可用 | “AI 审查不可用，已跳过” | 状态转 `AI_UNAVAILABLE`，**需显式 proceed 才继续**；规则结论保留 | AI 配置 | 是 |
| ~~`TASK_IN_PROGRESS`~~ | 已有进行中任务 | **该错误码不存在**：幂等命中时**直接返回已有任务**，不报错 | —— | 幂等键 | 否 |
| `BATCH_PARTIAL_FAILURE` | 批量中单份失败 | "第 N 份失败：原因" | 继续执行其余 | 批量日志 | 是 |

## 6. 测试与验收

### 单元测试（严格 TDD）

| 场景 | 类型 | 前置条件 | 操作 | 预期结果 |
| --- | --- | --- | --- | --- |
| 合法迁移 | 单元 | 状态 `PARSED` | 触发抽取完成 | 迁移到 `EXTRACTED` |
| 非法迁移被拒 | 单元 | 状态 `UPLOADED` | 直接触发 `COMPLETED` | `canMoveTo` 返回 false（**不是一个错误码**），状态不变 |
| 状态迁移穷举 | 单元 | 全部状态对 | 遍历所有组合 | 仅白名单组合成功 |
| 幂等：重复提交 | 单元 | 已存在同 key 任务 | 再次提交 | 返回同一 taskId，不新建 |
| AI 不可用降级 | 单元 | `AI_UNAVAILABLE` 事件 | 处理 | 转 `REVIEWING`，**不是终态** |
| 重复复核被拒 | 单元 | 条目已 `ACCEPTED` | 再次复核 | `ALREADY_REVIEWED` |
| 审计只追加 | 单元 | 已有 `ReviewAction` | 复核两次 | 记录数 = 2，**历史记录内容未被修改** |
| 审计含快照 | 单元 | 复核一条 AI 条目 | 写入 | 快照中保留原始 AI 建议原文与置信度 |
| 低置信度权限 | 单元 | 角色 `LEGAL_STAFF` | 终审低置信度条目 | `INSUFFICIENT_ROLE` |
| 只读账号 | 单元 | 角色 `DEMO_READONLY` | 任意复核 | 403 |
| 批量串行 | 单元 | 5 份合同 | 执行 | 实际执行为串行，无并发重叠 |
| 批量暂停 | 单元 | 执行到第 3 份时暂停 | 继续 | 不再发起新调用；恢复后从第 4 份开始 |

### 集成测试

| 场景 | 依赖替身 | 覆盖的验收项 |
| --- | --- | --- |
| 全链路状态推进 | `@SpringBootTest` + H2 | 端到端 |
| AI 未配置时全流程 | 配置桩（`app.ai.enabled=false`） | A-08 |
| 复核 + 审计查询 | `@SpringBootTest` + H2 | A-09 |

### 手工验收

| 步骤 | 预期结果 |
| --- | --- |
| 采信一条、驳回一条 | 两条都有操作人/时间/理由；AI 原始建议仍可查 |
| 清空 API Key 跑一遍 | 流程走完，规则结论正常，AI 部分明确标注不可用 |

## 7. 实现定位

- 主要代码位置：`src/main/java/com/demo/contract/workflow`（`statemachine/`、`review/`、`batch/`）
- 测试位置：`src/test/java/com/demo/contract/workflow`
- 数据库迁移：`V7__review_task.sql`、`V8__review_action.sql`
- 相关配置：`app.workflow.*`、`app.ai.low-confidence-threshold（application.yml:100）`
- 关联任务：[T-008（删除级联）、T-017 ~ T-019](../04-tasks-and-acceptance.md#待开始)

## 8. 长期决策与待办

### 稳定决策

| 日期 | 决策 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-10-03 | 审计表只 insert，原始 AI 行永不修改 | 可追溯性：任何结论都能回答"谁以什么理由改的" | I-06、report |
| 2026-10-03 | `AI_UNAVAILABLE` 不是终态，转入 `REVIEWING` | 否则 AI 故障会让整个审查作废 | I-04 |
| 2026-10-03 | 批量刻意串行，不并发 | 外部限流下并发放大失败；且要保证命令归属可追踪 | F-10 |
| 2026-10-03 | 裁决状态与审计记录分表 | 当前态需要可变，审计需要不可变，两种需求不能放一张表 | 数据模型 |

### 面试可讲点

- **审计为什么分两张表？** → 当前裁决态需要被更新（"这条现在是已采信"），审计记录必须不可变（"谁在何时因为什么采信了它"）。把两者放一张表，要么牺牲可追溯性，要么每次查询都要扫历史。**一个是状态，一个是事实，不能混。**
- **AI 不可用为什么不是失败终态？** → 因为系统里有两条独立通道。AI 挂了，确定性规则和人工复核照常工作，任务应该继续走完，只是在报告里标注"AI 部分未执行"。这是 I-04 的具体落点。
- **批量为什么串行？** → 外部服务有速率限制，并发的边际收益接近零，但会把失败放大、把问题归属搞乱。串行 + 节流 + 暂停恢复，比"看起来更快"的并发更可靠。
- **幂等键怎么选？** → 用 `tenantId + fileHash`。同一个文件重复提交必须落到同一个任务，否则会重复调用模型、产生两份互相矛盾的结论。
- **状态机怎么测？** → 穷举所有状态对，断言只有白名单中的迁移能成功。这样以后加状态时，非法路径会被测试自动拦住。

### 待办

- [ ] 撤回/修改已复核结论的流程（本期明确不做，需登记为 N 类）
- [ ] 批量任务的失败重试策略（当前仅记录并继续）
- [ ] 状态机是否需要落库为可配置（当前硬编码，符合 Lean Mode）
