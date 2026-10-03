---
title: AI 审查 ai-review
aliases:
  - ai-review
  - AI审查
tags:
  - 项目
  - 模块
  - AI
status: 未开始
---

# AI 审查 ai-review

## 0. 一句话定位

调用大模型识别**语义层面的风险条款**，但只产出「带原文证据和置信度的候选结论」——永不产出生效结论。

**不变量**：I-02（无原文定位不得生效）、I-04（AI 不可用可降级）、I-05（幂等与缓存）。

## 1. 职责与边界

### 模块职责

条款切分 → 提示词组装（含固定输出 schema）→ 调用大模型 → schema 校验 → **证据对齐** → 置信度评分 → 产出候选 `AiFinding`。
成本治理（缓存、限流、预算上限）也归本模块。

### 不负责什么

- 不决定候选结论是否生效 → 交给 [workflow](workflow.md) 的人工复核
- 不做确定性校验 → 交给 [rule](rule.md)
- 不提取结构化要素 → 交给 [extract](extract.md)（但复用其证据对齐组件）

### 上下游关系

| 方向 | 模块/系统 | 交换内容 | 约定 |
| --- | --- | --- | --- |
| 输入 | [parse](parse.md) | 归一化文本 + 段落偏移 | 同上游 |
| 输入 | 大模型 API | JSON 风险条目 | **必须过 schema 校验 + 证据对齐** |
| 输出 | [workflow](workflow.md) | `AiFinding[]`（状态恒为 `PENDING` / `EVIDENCE_MISMATCH`） | 本模块**从不**输出"已确认"状态 |

### 允许的依赖

- 允许调用：[parse](parse.md)、[extract](extract.md) 的证据对齐组件、LLM 客户端、Redis、MySQL
- **禁止调用**：[rule](rule.md)。确定性与概率性判断必须隔离，只在 [workflow](workflow.md) 汇合。

## 2. 功能清单

| 编号 | 功能 | 输入 | 输出 | 关联需求 |
| --- | --- | --- | --- | --- |
| M-I01 | 条款切分 | 段落列表 | 条款块 + 偏移 | F-05 |
| M-I02 | 风险类型定义与提示词 | 条款块 | 模型响应 | F-05 |
| M-I03 | schema 校验 | 响应文本 | 结构化条目 或 `SCHEMA_INVALID` | A-07 |
| M-I04 | 证据对齐与降级 | quote + 原文 | 区间 或 `EVIDENCE_MISMATCH` | A-06 |
| M-I05 | 置信度评分 | 模型自评 + 匹配级别 | `confidence` | A-05 |
| M-I06 | 缓存 / 限流 / 预算 | `textHash` + 版本 | 命中或拒绝 | I-05 |

## 3. 核心流程

### 流程：AI 风险审查

1. 条款切分：按段落与长度切块，记录每块在归一化文本中的偏移。
2. 组装提示词：固定风险类型枚举 + 固定输出 schema，**明确要求每个结论附带原文 quote**。
3. 调用大模型（受令牌桶限流与预算检查约束）。
4. **schema 校验**：缺字段、类型错、枚举值越界 → `SCHEMA_INVALID`，丢弃本次响应，**不填默认值、不部分采纳**。
5. **证据对齐**：复用 [extract](extract.md) 的对齐算法（L1 精确 / L2 归一化回映射 / L3 模糊降置信）。
   - 对齐失败 → 条目状态 `EVIDENCE_MISMATCH`，**不进入报告正文**，转人工。
6. **置信度评分**：
   `finalConfidence = modelSelfScore × matchLevelWeight × lengthPenalty`
   - `matchLevelWeight`：L1 = 1.0，L2 = 0.9，L3 = 0.7
   - `lengthPenalty`：quote 过短（如 < 8 字）时下调，避免"一句话命中全文"的伪证据
7. 状态裁决：
   - `finalConfidence ≥ highThreshold` → `PENDING`（等待人工采信）
   - `lowThreshold ≤ finalConfidence < highThreshold` → `PENDING` + 标记需主管复核
   - `< lowThreshold` → `LOW_CONFIDENCE`，强制人工，不进报告正文
8. 落 `ai_finding`，附模型版本与提示词版本。**所有条目初始状态都不是"生效"。**

### 风险类型（固定枚举）

| 风险类型 | 含义 | 默认严重级别 |
| --- | --- | --- |
| `UNLIMITED_LIABILITY` | 责任无上限或"一切损失" | HIGH |
| `UNILATERAL_TERMINATION` | 单方无理由解除权 | HIGH |
| `AUTO_RENEWAL` | 自动续约且无退出方式 | MEDIUM |
| `VAGUE_PAYMENT` | 付款条件/时点不明确 | MEDIUM |
| `UNCAPPED_PENALTY` | 违约金过高或计算不明 | HIGH |
| `CONFIDENTIALITY_GAP` | 保密义务单方或缺失 | MEDIUM |
| `JURISDICTION_UNCLEAR` | 争议解决方式不明确 | MEDIUM |
| `UNKNOWN_RISK` | 模型认为有风险但无法归类 | LOW（**必须转人工**） |

> [!important] 为什么要有 `UNKNOWN_RISK`
> 强行把所有发现塞进已有枚举，会让模型"编造最接近的类型"，反而降低可信度。
> 给它一个诚实的出口，比逼它分类更好——这与 [rule](rule.md) 的 `UNDETERMINED` 是同一条原则。

### 异常与降级路径

| 情况 | 判定方式 | 系统行为 | 是否转人工 |
| --- | --- | --- | --- |
| API Key 未配置 | 启动时检测 | 模块标记为**不可用**，接口返回 `AI_UNAVAILABLE`；规则与人工流程不受影响（I-04） | 是 |
| 调用超时 | 客户端超时 | 重试 1 次 → 仍失败 `AI_TIMEOUT` | 是 |
| 限流 429 | 状态码 | 退避后重试 1 次 → `AI_RATE_LIMITED` | 是 |
| 响应非法 | schema 校验 | `SCHEMA_INVALID`，丢弃，无脏数据 | 是 |
| 证据不可定位 | 对齐失败 | `EVIDENCE_MISMATCH`，不进报告正文 | 是 |
| 证据多处命中 | 无法消歧 | `EVIDENCE_AMBIGUOUS`，转人工选择 | 是 |
| 缓存命中 | `textHash` + 版本命中 | 零外部调用直接返回 | 否 |
| 超预算 | 日调用量超上限 | **明确拒绝**（`AI_BUDGET_EXCEEDED`），**不静默降级为"审查通过"** | 是 |
| Redis 不可用 | 连接异常 | 缓存与限流失效 → **降级为拒绝调用**（保护成本），规则链照常 | 是 |

> [!warning] 两条绝对红线
> 1. **AI 失败绝不能被表述成"未发现风险"。** 用户看到"无风险"和"没审成"是两件完全不同的事。
> 2. **任何 AI 结论都不得跳过人工直接进报告正文当确定结论。**

## 4. 数据与接口

### 数据结构

| 字段/对象 | 类型 | 含义 | 必填 | 来源 | 去向 |
| --- | --- | --- | --- | --- | --- |
| `AiFinding.riskType` | Enum | 见上方枚举 | 是 | 模型 | 前端、报告 |
| `AiFinding.quote` | String | 支撑结论的原文引文 | 是 | 模型 | 证据对齐、前端高亮 |
| `AiFinding.charStart/charEnd` | int | **原文**区间 | 对齐成功时必填 | 对齐算法 | 前端、报告 |
| `AiFinding.confidence` | BigDecimal | 最终置信度 | 是 | 评分公式 | workflow 裁决 |
| `AiFinding.modelVersion` | String | 模型版本 | 是 | 配置 | 可追溯性 |
| `AiFinding.promptVersion` | String | 提示词版本 | 是 | 配置 | 缓存键 + 可追溯 |
| `AiFinding.status` | Enum | `PENDING` / `LOW_CONFIDENCE` / `EVIDENCE_MISMATCH` / `EVIDENCE_AMBIGUOUS` | 是 | 本模块 | workflow |

### 接口/事件

| 名称 | 调用方 | 输入 | 成功输出 | 失败输出 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `POST /api/contracts/{id}/ai-review` | 前端、workflow | contractId | 候选条目列表 | `AI_UNAVAILABLE` / `AI_BUDGET_EXCEEDED` / `AI_RATE_LIMITED` | 幂等：同 textHash 复用缓存 |
| `GET /api/contracts/{id}/findings?type=AI` | 前端 | contractId | AI 候选列表 | 404 | 强制 tenantId |
| 领域事件 `AiReviewCompleted` | ai-review 发布 | contractId, pendingCount, mismatchCount | — | — | workflow 推进状态 |

## 5. 状态、错误码与排查

| 错误码 | 触发条件 | 用户可见结果 | 系统行为 | 优先排查位置 | 是否可重试 |
| --- | --- | --- | --- | --- | --- |
| `AI_UNAVAILABLE` | 未配置 Key / 依赖不可用 | "AI 审查暂不可用，规则审查不受影响" | 跳过 AI 环节，状态机继续 | AI 配置检测 | 配置后重试 |
| `AI_TIMEOUT` | 调用超时 | "AI 审查超时" | 重试 1 次后放弃 | 超时配置 | 是 |
| `AI_RATE_LIMITED` | 429 | "AI 服务繁忙" | 退避重试 1 次 | 限流配置 | 是 |
| `AI_BUDGET_EXCEEDED` | 超日预算 | "今日 AI 审查额度已用尽" | **拒绝调用** | 预算计数器 | 次日 |
| `SCHEMA_INVALID` | 响应结构不符 | "AI 返回格式异常" | 丢弃，无脏数据 | schema 校验器 + 提示词版本 | 是 |
| `EVIDENCE_MISMATCH` | 对齐失败 | "该条证据无法定位，已转人工" | 不进报告正文 | 对齐算法 | 是（人工） |
| `EVIDENCE_AMBIGUOUS` | 多处命中 | "存在多个可能位置" | 转人工选择 | 对齐算法 | 是 |
| `LOW_CONFIDENCE` | 置信度低于阈值 | "该条置信度低，需主管复核" | 强制升级复核 | 阈值配置 | 是 |

## 6. 测试与验收

### 单元测试（严格 TDD，无网络无 DB）

| 场景 | 类型 | 前置条件 | 操作 | 预期结果 |
| --- | --- | --- | --- | --- |
| 置信度评分 L1 | 单元 | 自评 0.9 + L1 匹配 | 评分 | 0.9 |
| 置信度评分 L3 下调 | 单元 | 自评 0.9 + L3 匹配 | 评分 | 显著低于 0.9 |
| 过短 quote 惩罚 | 单元 | quote = 4 字 | 评分 | 触发 `lengthPenalty` |
| 低置信度转人工 | 单元 | 分数 < 低阈值 | 裁决 | `LOW_CONFIDENCE`，不进正文 |
| schema 缺字段 | 单元 | 响应少一个必填字段 | 校验 | `SCHEMA_INVALID`，不产生条目 |
| 枚举越界 | 单元 | `riskType` 不在枚举内 | 校验 | `SCHEMA_INVALID` |
| 证据对齐失败 | 单元 | quote 不存在于原文 | 对齐 | `EVIDENCE_MISMATCH` |
| 缓存命中零调用 | 单元 | 同 `textHash` + 版本 | 二次审查 | 外部调用 = 0 |
| 超预算拒绝 | 单元 | 预算计数达上限 | 调用 | `AI_BUDGET_EXCEEDED`，**不返回"无风险"** |

### 集成测试

| 场景 | 依赖替身 | 覆盖的验收项 |
| --- | --- | --- |
| 正常审查落库 | `AiClient` Mock 桩 + H2 | A-05 |
| 非法 JSON | Mock 桩返回非法 JSON | A-07 |
| 错误区间（quote 造假） | Mock 桩返回不存在的 quote | A-06 |
| 超时 / 限流 | Mock 桩模拟延迟 / 429 | 降级路径 |
| 未配置 Key | 配置桩 | A-08 |
| Redis 不可用 | 故障注入 | 降级为拒绝调用，规则链正常 |

### 手工验收

| 步骤 | 预期结果 |
| --- | --- |
| 执行 AI 审查，查看任一条目 | 含风险类型、引用、区间、置信度、模型版本 |
| 清空 API Key 后重启并打开合同 | 明确提示 AI 不可用，规则结论正常显示 |

## 7. 实现定位

- 主要代码位置：`src/main/java/com/demo/contract/aireview`
- 证据对齐复用：`src/main/java/com/demo/contract/extract/evidence/`
- 测试位置：`src/test/java/com/demo/contract/aireview`
- 桩数据：`src/test/resources/wiremock/ai-review-*.json`
- 数据库迁移：`V6__ai_finding.sql`
- 相关配置：`app.ai.*`（`enabled`、超时、阈值、预算、模型与提示词版本）
- 关联任务：[T-014、T-016](../04-tasks-and-acceptance.md#待开始)

## 8. 长期决策与待办

### 稳定决策

| 日期 | 决策 | 原因 | 影响 |
| --- | --- | --- | --- |
| 待填 | AI 结论恒为"候选"，采信权归人工 | 概率性输出的错误代价由业务承担 | I-02、workflow |
| 待填 | 证据对齐失败即降级，不返回近似区间 | 无法核实的结论比没有结论更糟 | 报告可信度 |
| 待填 | schema 非法整体丢弃，不部分采纳 | 部分采纳会产出结构不完整的脏数据 | 数据质量 |
| 待填 | 置信度 = 自评 × 匹配级别 × 长度惩罚 | 单靠模型自评不可靠，必须与"证据有多硬"绑定 | 复核分流 |
| 待填 | 超预算显式拒绝，不降级为"无风险" | 把"没审"伪装成"没问题"是最严重误导 | 安全边界 |
| 待填 | 保留 `UNKNOWN_RISK` 出口 | 逼模型分类会得到编造的分类 | 结果可信度 |

### 面试可讲点

- **这是我最想被问的问题：你怎么保证 AI 不胡说？** → 四道闸门，逐层收紧：① 提示词固定 schema；② 响应做 schema 校验，不合法整体丢弃，**不用默认值兜底**；③ 证据对齐，quote 必须能在原文坐标命中，否则转人工；④ 最终永远只是"候选"，人工采信才生效。核心思路是：**不试图让模型更准，而是让它的错误变得可检测。**
- **置信度怎么算的，为什么这么算？** → `模型自评 × 匹配级别权重 × 长度惩罚`。只用自评不行，模型普遍高估自己；匹配级别反映"证据有多硬"（精确命中 vs 模糊匹配）；长度惩罚防止模型引用四个字就说"全文都在讲这个"。**本质是把"证据强度"显式变成可裁决的数字。**
- **AI 挂了会怎样？** → 规则链和人工复核完全不依赖 AI，服务照常可用，界面明确提示 `AI_UNAVAILABLE`。这是设计出来的降级路径，不是碰巧（I-04）。
- **成本怎么控？** → 按 `textHash + promptVersion + modelVersion` 缓存；令牌桶限流；日预算上限；超预算**明确拒绝**。特别注意：超预算绝不能表现为"审查通过、未发现风险"。
- **重试几次？** → 最多 1 次。无限重试会把限流放大成雪崩，而且会掩盖真正的问题。
- **为什么 AI 结论不能自动生效？** → 这是我整个项目的核心判断：**AI 的错误不是"偶尔出错"，而是"无法知道它什么时候出错"**。既然错误不可预测，就必须保留人工否决权。有评测集和稳定准确率之后，才谈自动生效——而我明确承认现在没有评测集。

### 待办

- [ ] 提示词版本变更后旧缓存的处理策略
- [ ] 评测集建设（已知短板，见 [07](../07-change-and-delivery.md#已知限制)）
- [ ] 预算计数的持久化与重置时机（当前设计待确认）
