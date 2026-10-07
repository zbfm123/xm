---
title: 要素抽取 extract
aliases:
  - extract
  - 要素抽取
tags:
  - 项目
  - 模块
  - AI
status: 已完成
---

# 要素抽取 extract

## 0. 一句话定位

把归一化文本变成**带原文坐标和置信度的结构化要素**，抽取不到的字段显式标 `UNKNOWN`，绝不猜。

**不变量**：I-02（无原文定位的结论不得生效）。

## 1. 职责与边界

### 模块职责

章节切块 → 调用大模型 → **schema 校验** → **证据对齐**（把模型给的 quote 定位到原文区间）→ 字段级置信度 → 落库。
本模块同时产出「证据对齐算法」这一核心组件，被 [ai-review](ai-review.md) 复用。

### 不负责什么

- 不做合规判定（金额是否合理、条款是否缺失）→ 交给 [rule](rule.md)
- 不做风险评价（是否不利条款）→ 交给 [ai-review](ai-review.md)
- 不决定要素是否生效 → 交给 [workflow](workflow.md) 的人工复核

### 上下游关系

| 方向 | 模块/系统 | 交换内容 | 约定 |
| --- | --- | --- | --- |
| 输入 | [parse](parse.md) | `NormalizedText` | 必须带坐标映射 |
| 输入 | 大模型 API | 结构化字段 JSON | **必须过 schema 校验**才使用 |
| 输出 | [rule](rule.md)、[workflow](workflow.md) | `ContractElement[]` | 每项含 `value / charStart / charEnd / confidence / source` |

### 允许的依赖

- 允许调用：[parse](parse.md)、LLM 客户端抽象、MySQL、Redis（缓存）
- 禁止调用：[rule](rule.md)、[ai-review](ai-review.md)、[workflow](workflow.md)

## 2. 功能清单

| 编号 | 功能 | 输入 | 输出 | 关联需求 |
| --- | --- | --- | --- | --- |
| M-E01 | 章节切块 | 段落列表 | 语义块 + 偏移 | F-03 |
| M-E02 | 抽取提示词与 schema 约定 | 语义块 | 模型响应 | F-03 |
| M-E03 | schema 校验 | 响应文本 | 结构化对象 或 `SCHEMA_INVALID` | A-07 |
| M-E04 | **证据对齐算法** | quote + 原文 + 坐标映射 | `[charStart, charEnd]` 或"对齐失败" | A-03 / A-06 |
| M-E05 | 字段级置信度与状态 | 对齐结果 + 模型自评 | `confidence` + `CONFIRMED/LOW_CONFIDENCE/UNKNOWN` | A-03 |
| M-E06 | 结果缓存 | **`operation`** + `textHash` + 提示词版本 | 命中则零外部调用 | I-05 |

## 3. 核心流程

### 流程：要素抽取

1. 按段落切块（保留每块在归一化文本中的偏移）。
2. 以 `ai:result:extract:<textHash>:<promptVersion>:<model>` 为键查缓存；命中直接返回。
   ⚠️ 键里的 `operation` 段是必须的：抽取与审查的响应形状不同，**共用一个键会互相覆盖**（真踩过，9 个测试失败）。
3. 组装提示词，要求模型按固定 schema 输出，每个字段**必须附带原文引文 quote**。
4. 收响应：
   - 非法 JSON / 字段缺失 / 类型不符 → `SCHEMA_INVALID`，**丢弃本次输出，不填默认值**。
5. 对每个字段的 quote 执行**证据对齐**（见下）：
   - 命中 → 记录归一化区间 → 通过 `offsetMap` 回映射为原文区间；
   - 未命中 → 该字段置 `UNKNOWN`，原因 `EVIDENCE_NOT_FOUND`。
6. 计算字段状态：
   - 对齐成功且模型置信度 ≥ 阈值 → `CONFIRMED`；
   - 对齐成功但置信度 < 阈值 → `LOW_CONFIDENCE`（进入人工确认）；
   - 对齐失败 → `UNKNOWN`。
7. 落 `contract_element`，同合同同字段的新结果以新版本追加，不覆盖历史。

### 证据对齐算法（本项目核心，单独 TDD）

**输入**：quote（模型给的引文）、归一化文本、`offsetMap`。
**输出**：原文区间，或明确的失败原因。

分级匹配策略：

| 级别 | 策略 | 判定 |
| --- | --- | --- |
| L1 | 精确匹配 | quote 在归一化文本中唯一出现 → 直接取区间 |
| L2 | 归一化匹配 | 双方统一全角/空白/换行后再匹配；**命中后必须把区间回映射到原文坐标** |
| L3 | 模糊匹配 | 编辑距离/最长公共子串比例 ≥ 阈值 → 取最优区间，并把置信度**下调** |
| 失败 | 以上都不成立 | 返回 `EVIDENCE_NOT_FOUND`，**不返回近似区间** |

> [!warning] 三条纪律
> 1. 匹配到**多处**且无法消歧时，不算成功，走 `EVIDENCE_AMBIGUOUS`。
> 2. L2/L3 的区间必须回映射，**不能把归一化坐标直接当成原文坐标**（这是我踩过的坑）。
> 3. 宁可判失败转人工，**也不返回一个"差不多"的区间**。

### 异常与降级路径

| 情况 | 判定方式 | 系统行为 | 是否转人工 |
| --- | --- | --- | --- |
| 模型超时 | 客户端超时 | 最多重试 1 次 → `AI_TIMEOUT` | 是 |
| 模型限流 | 429 | 最多重试 1 次 → `AI_RATE_LIMITED` | 是 |
| 响应非法 | schema 校验失败 | `SCHEMA_INVALID`，丢弃，不落数据 | 是 |
| quote 不存在 | 对齐 L1~L3 全失败 | 字段 `UNKNOWN` + `EVIDENCE_NOT_FOUND` | 是 |
| quote 多处命中 | 匹配结果 > 1 且无法消歧 | `EVIDENCE_AMBIGUOUS` | 是 |
| 缓存不可用 | Redis 异常 | **直连模型并继续**（缓存是优化，不是必需） | 否 |

## 4. 数据与接口

### 数据结构

| 字段/对象 | 类型 | 含义 | 必填 | 来源 | 去向 |
| --- | --- | --- | --- | --- | --- |
| `ContractElement.fieldKey` | String | 字段名，如 `amount` / `partyA` / `signDate` | 是 | 提示词 schema | rule |
| `ContractElement.value` | String | 抽取值 | 是 | 模型 | rule、前端 |
| `ContractElement.quote` | String | 支撑该值的原文引文 | 是 | 模型 | 证据对齐、前端高亮 |
| `ContractElement.charStart/charEnd` | int | **原文**区间 | 对齐成功时必填 | 对齐算法 | 前端、报告 |
| `ContractElement.confidence` | BigDecimal | 0~1 | 是 | 模型自评 + 匹配级别修正 | workflow 判定 |
| `ContractElement.status` | Enum | `CONFIRMED` / `LOW_CONFIDENCE` / `UNKNOWN` | 是 | 本模块 | workflow、rule |
| `ContractElement.source` | Enum | `LLM` / `REGEX` | 是 | 本模块 | 面试可讲：混合策略 |

> [!tip] 一个诚实的设计点
> 金额、日期这类**格式强、可正则**的字段，优先走正则并只用模型做交叉验证：
> 正则结果确定、零成本；模型结果用于发现正则遗漏。
> 两者冲突时不自动取其一，而是产出 `CONFLICT` 提示 → 转人工。

### 接口/事件

| 名称 | 调用方 | 输入 | 成功输出 | 失败输出 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `POST /api/contracts/{id}/extract` | 前端 | contractId | 要素列表 + 各字段状态 | `AI_TIMEOUT` / `SCHEMA_INVALID` | 幂等：同 textHash 复用缓存 |
| `GET /api/contracts/{id}/elements` | 前端、rule | contractId | `ContractElement[]` | 404 | 强制 tenantId |
| 领域事件 `ElementsExtracted` | extract 发布 | contractId, confirmedCount, unknownCount | — | — | workflow 据此决定是否可进入规则校验 |

## 5. 状态、错误码与排查

| 错误码 | 触发条件 | 用户可见结果 | 系统行为 | 优先排查位置 | 是否可重试 |
| --- | --- | --- | --- | --- | --- |
| `SCHEMA_INVALID` | 响应结构不符约定 | "AI 返回格式异常" | 丢弃本次输出，无脏数据 | schema 校验器 + 提示词版本 | 是（人工触发） |
| `AI_TIMEOUT` | 调用超时 | "抽取超时，请重试" | 重试 1 次后放弃 | LLM 客户端超时配置 | 是 |
| `AI_RATE_LIMITED` | 429 | "AI 服务繁忙" | 重试 1 次 + 退避 | 限流配置 | 是 |
| `EVIDENCE_NOT_FOUND` | 对齐失败 | "该字段无法定位到原文，请人工确认" | 字段 `UNKNOWN` | 对齐算法 + 归一化映射 | 是（人工） |
| `EVIDENCE_AMBIGUOUS` | 多处命中 | "该字段存在多个可能位置" | 转人工选择 | 对齐算法 | 是 |
| `FIELD_CONFLICT` | 正则与模型结果不一致 | "金额存在两种取值，请确认" | 两值都保留，转人工 | 正则 + 模型交叉校验 | 是 |

> [!warning] 绝对不允许
> `catch (Exception e) { return defaultValue; }`
> 一旦用默认值兜底，系统会产出**看起来正常但错误**的要素，而 [rule](rule.md) 会基于它给出"合规"结论——这是本项目最严重的一类 bug。

## 6. 测试与验收

### 单元测试（严格 TDD，无网络无 DB）

| 场景 | 类型 | 前置条件 | 操作 | 预期结果 |
| --- | --- | --- | --- | --- |
| L1 精确匹配 | 单元 | quote 与原文一致 | 对齐 | 返回正确区间 |
| L2 归一化匹配 | 单元 | quote 含全角标点 | 对齐 | 命中且区间**回映射到原文坐标**（长度变化场景） |
| L3 模糊匹配降置信 | 单元 | quote 有 1 字差异 | 对齐 | 命中但 `confidence` 下调 |
| quote 不存在 | 单元 | 构造不存在的 quote | 对齐 | `EVIDENCE_NOT_FOUND`，不返回近似区间 |
| quote 多处命中 | 单元 | 同一句出现两次 | 对齐 | `EVIDENCE_AMBIGUOUS` |
| schema 非法 | 单元 | JSON 缺字段/类型错 | 校验 | `SCHEMA_INVALID`，不产生要素 |
| 低置信度状态 | 单元 | 置信度低于阈值 | 计算状态 | `LOW_CONFIDENCE` |
| 正则模型冲突 | 单元 | 两者结果不同 | 交叉校验 | `FIELD_CONFLICT`，两值保留 |
| 缓存命中零调用 | 单元 | 同 textHash 二次调用 | 抽取 | 外部调用次数 = 0 |

### 集成测试

| 场景 | 依赖替身 | 覆盖的验收项 |
| --- | --- | --- |
| 正常抽取 + 落库 | `AiClient` Mock 桩 + H2 | A-03 |
| 非法 JSON 响应 | `AiClient` Mock 桩返回非法 JSON | A-07 |
| 超时与限流 | `AiClient` Mock 桩模拟延迟与 429 | 降级路径 |
| Redis 不可用 | 桩 | 直连模型仍成功 |

### 手工验收

| 步骤 | 预期结果 |
| --- | --- |
| 提取要素后点开 `amount` 字段 | 前端高亮原文对应片段，区间准确 |

## 7. 实现定位

- 主要代码位置：`src/main/java/com/demo/contract/extract`（含 `evidence/EvidenceAligner.java`）
- 测试位置：`src/test/java/com/demo/contract/extract`
- 桩数据：`src/test/resources/wiremock/`
- 数据库迁移：`V4__contract_element.sql`
- 相关配置：`app.ai.*`（超时、重试次数、置信度阈值、模型版本）
- 关联任务：[T-013 ~ T-015](../04-tasks-and-acceptance.md#待开始)

## 8. 长期决策与待办

### 稳定决策

| 日期 | 决策 | 原因 | 影响 |
| --- | --- | --- | --- |
| 2026-10-03 | 抽取不到的字段标 `UNKNOWN`，不用默认值 | 默认值会伪装成合法数据，污染 rule 结论 | I-02、rule |
| 2026-10-03 | 证据对齐分三级匹配，L2/L3 必须回映射坐标 | 归一化会改变长度，直接当原文坐标必然错 | 全链路证据 |
| 2026-10-03 | 金额/日期优先正则，模型做交叉验证 | 确定性字段不该付概率成本；冲突时暴露而非吞掉 | rule 输入质量 |
| 2026-10-03 | 缓存不可用时降级为直连，而非失败 | 缓存是优化不是依赖 | I-04 |

### 面试可讲点

- **你怎么处理大模型的幻觉？** → 分两步：结构上做 schema 校验（不合法就丢弃，不填默认值），内容上做证据对齐（quote 必须能在原文命中，否则 `UNKNOWN` 转人工）。**关键是不给它"编一个值"的机会。**
- **证据对齐为什么分级？** → L1 精确匹配最可信但不实用（模型总会有细微改写）；L2 归一化匹配覆盖大多数情况但坐标必须回映射；L3 模糊匹配能用但必须下调置信度。**分级的本质是把"匹配强度"显式变成"置信度"。**
- **为什么金额优先用正则？** → 金额格式确定，正则零成本、零延迟、可复现；模型的价值在于发现正则漏掉的表达方式。两者冲突时不自动选一个，而是暴露给人工——**冲突本身就是有价值的信息**。
- **`UNKNOWN` 为什么不等于失败？** → 它是一个**业务状态**，会触发人工复核流程。把它和"系统错误"混为一谈，就没法区分"模型说不知道"和"系统挂了"。

### 待办

- [ ] 提示词版本管理策略（版本号进缓存键，避免改提示词后命中旧缓存）
- [ ] 字段 schema 的演进方式（新增字段时历史数据如何处理）
