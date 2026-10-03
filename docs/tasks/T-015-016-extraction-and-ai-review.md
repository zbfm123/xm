---
title: T-015 / T-016 要素抽取 · AI 风险审查 · 证据对齐接入
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-015 / T-016 要素抽取 · AI 风险审查 · 证据对齐接入

## 任务边界

- 所属模块：[extract](../modules/extract.md)、[ai-review](../modules/ai-review.md)
- 对应需求/验收：**F-05 / F-06 / A-05 / A-06 / A-08**、不变式 **I-02 / I-04**
- 目标行为：
  - 模型输出经 schema 校验 → 证据对齐 → 置信度裁决 → 落库
  - **引文定位不到的结论不进报告正文**
  - AI 结论的初始状态**永远不是"已生效"**
- 前置依赖：T-013（证据对齐）、T-014（AI 客户端）✅
- 涉及的不变式：**I-02**（无定位不进正式结论）、**I-04**（AI 挂了规则链仍可用）

## 四道闸门

逐层收紧，每一步都在减少"不可信输入"的通过率：

```
模型输出
  │
  ├─ 1. schema 校验
  │     结构非法 / riskType 越界 / confidence 越界 → 整体丢弃（SCHEMA_INVALID）
  │     **不部分采纳、不填默认值、不静默截断**
  │
  ├─ 2. 证据对齐
  │     引文必须在原文定位到；失败 → EVIDENCE_MISMATCH / EVIDENCE_AMBIGUOUS
  │     **该条不进报告正文**，只作为人工待办
  │
  └─ 3. 置信度裁决
        置信度 = 模型自评 × 匹配级别权重 × 引文长度惩罚
        低于阈值 → LOW_CONFIDENCE，必须人工（主管）复核

  4. 状态机：所有条目的初始状态都是候选态，**没有"已生效"这个状态**
```

> [!important] 核心思路
> **不是让模型更准，而是让它的错误变得可检测。**

## 置信度公式为什么是三项相乘

| 因子 | 含义 | 缺了它会怎样 |
| --- | --- | --- |
| 模型自评 | 模型对自己的判断有多确信 | — |
| **匹配级别权重** | 证据有多硬（EXACT 1.0 / NORMALIZED 0.9 / FUZZY 0.7） | "引文有一处不同"与"逐字相同"被同等对待 |
| **引文长度惩罚** | 防止"引四个字就说全文都在讲这个" | 短引文获得与长引文相同的可信度 |

只用模型自评是不行的：**模型普遍高估自己**。把"证据强度"乘进去，
这个数字才真正反映"这条结论有多值得信"。

## 验收示例

| 情况 | 期望 |
| --- | --- |
| 正常引用 | 对齐成功，落库带区间、级别、置信度 |
| 引文在原文中不存在 | `EVIDENCE_MISMATCH`，**`char_start` 为 NULL**，不进报告正文 |
| 同一引文出现多次 | `EVIDENCE_AMBIGUOUS`，同样不进正文 |
| `riskType` 不在枚举内 | `SCHEMA_INVALID`，**整体丢弃，不当作 UNKNOWN_RISK** |
| `confidence` 为 1.7 或 "高" | `SCHEMA_INVALID`，**不静默截断到 0~1** |
| 缺 `quote` 字段 | `SCHEMA_INVALID`，**不用默认值补齐** |
| 要素引文定位失败 | 落库为 `UNKNOWN` + 原因，**但仍落库** |
| AI 通道未启用 | 抛 `AI_UNAVAILABLE`，由调用方降级；**规则校验照常可用** |
| 删除合同 | 要素行与 AI 结论行一并清理 |

### 明确不该发生的事

- 引文定位不到的结论出现在报告正文里
- "AI 不可用"与"未发现风险"在数据上无法区分
- 部分采纳一个结构非法的响应
- 用默认值补齐缺失字段后当正常结果用
- 重复审查累积旧结论

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `aireview/support/FindingSchemaValidator.java` | schema 校验 + **置信度计算** |
| `aireview/support/PromptTemplates.java` | 提示词与**版本号**（进缓存键） |
| `aireview/AiReviewService.java` | 风险审查四道闸门 |
| `aireview/domain/AiFindingRow.java`、`mapper/AiFindingMapper.java` | AI 结论持久化，含 `findReportable` |
| `extract/ElementExtractionService.java` | 要素抽取 + 转为规则可用的 `ElementLookup` |
| `extract/domain/ContractElementRow.java`、`mapper/ContractElementMapper.java` | 要素持久化 |
| `aireview/AiReviewController.java` | `/extract`、`/elements`、`/ai-review`、`/ai-findings` |

## 关键设计说明

### 1. 对齐失败的要素为什么仍要落库

如果直接丢弃，数据库里"没有这一行"将同时表示两种情况：
**"从没抽过"** 与 **"抽过但失败"**。人工复核时无法区分这两种状态。

因此落库为 `UNKNOWN` + `status_reason`，只是 `element_value` 为空。

### 2. `riskType` 越界为什么判整体失败

静默归类成 `UNKNOWN_RISK` 会掩盖"模型没有遵守约定"这个信号。
一旦掩盖，就再也发现不了提示词漂移。

### 3. 读取侧也做过滤

`findReportable()` 在 SQL 层面就加了 `char_start IS NOT NULL`。
**即使将来有人写了个新接口忘了过滤，也应该走这个方法。**
这是不变式 I-02 的第二道防线。

## TDD 记录

### 4. 验证结果

| 验证项 | 结果 |
| --- | --- |
| 集成测试 | ✅ `AiReviewIntegrationTest` 12 例 + `CascadeCleanupTest` 2 例 |
| **合计** | ✅ `Tests run: 213, Failures: 0, Errors: 0` / BUILD SUCCESS |
| **真实 MySQL 端到端** | ✅ 见下 |

#### 真实 MySQL 端到端结果

| 场景 | 结果 |
| --- | --- |
| 要素抽取（`well-formed`） | 4 个字段，引文定位 **4 成功 / 0 失败**，成功率 1.0 |
| 抽取明细 | 每项都带 `EXACT` 级别、置信度 0.91 / 0.7735、原文区间 |
| AI 审查（`risky` 模板） | **4 条候选**，全部 `PENDING`，可进正文 4 条 |
| 落库状态分布 | `ai_finding`：`PENDING 4`；**`char_start IS NULL` 的条数为 0** |
| 重复审查 | 清旧写新，库中行数不累积 |
| 跨租户读结论 | 返回空列表；跨租户执行审查 → 404 `CONTRACT_NOT_FOUND` |
| **删除合同的级联清理** | `contract_text` / `contract_element` / `ai_finding` **全部归 0** |

## 实施日志（短期）

修了 **3 个 bug**，其中第 3 个最有教育意义：

### 缺陷 1：`@ConditionalOnProperty` 没有让两个实现互斥

我在 T-014 的任务卡里刚写下"用 `@ConditionalOnProperty` 让两个 `AiClient` 互斥"，
结果**两个 bean 同时被注册**：

```
NoUniqueBeanDefinitionException: expected single matching bean but found 2:
deepSeekClient, mockAiClient
```

整个应用上下文起不来，63 个测试报错。

修复：不追条件注解的求值细节，改成**显式配置类**
（`AiClientConfig`），里面有且只有一个 `AiClient` bean，选谁由一个 `if` 决定。

> **教训：当"互斥"是硬要求时，用显式的分支表达它，而不是依赖注解的求值时机。**
> 条件注解的错误方式是"两个都在"或"一个都没有"，
> 而这两种情况都要等到上下文启动失败才被发现。

### 缺陷 2：H2 里 `VALUE` 是保留字

`contract_element` 的列名用了 `value`，MySQL 可以，H2 建表失败，
整个测试上下文起不来。

改列名为 `element_value` 后，又踩了后半截：**Java 属性仍叫 `value`**，
而项目没开下划线转驼峰，MyBatis 读回来时对不上——
**症状是 `value` 全为 null，但状态显示 `CONFIRMED`**（写了，读不到）。

修复：属性一并改名为 `elementValue`。这个名字还顺带避免在 `switch`
分支里与关键字混淆。

### 缺陷 3：删除合同漏了清理新表（孤儿数据）

`contract_element` 建表后忘了登记到 `ContractService` 的清理段。
结果：删除合同时正文与规则结论都清了，**要素却留在库里**。

这类 bug 的特点：**不报错、不影响功能，只在统计与存储上慢慢积累**。
人工几乎不可能发现。

修复：
- 清理段补上两张新表，并加注释说明"新增机器结论表必须在这里登记"
- 删除日志把每类清理行数都列出——**某类始终为 0 而实际有数据就是漏登记的痕迹**
- 新增 `CascadeCleanupTest`，并刻意先断言"数据确实写进去了"，
  否则"删除后为 0"可能是假绿

### 测试断言修正 2 处（代码是对的）

- `cascadeShouldNotTouchOtherContracts` 里两份合同正文相同，
  被**内容哈希幂等**合并成同一份，测试退化成"只有一份合同"。
  给它们不同内容，并加断言防止将来再犯。
- 风险审查的演示用 `well-formed` 模板，但它**没有风险条款**，
  结果 AI 审查返回 0 条，验证脚本什么都没验到。

  这暴露了一个更普遍的问题：**演示素材必须自带触发路径的内容。**
  因此：
  - 新增 `risky` 模板（含四类典型风险条款）用于演示 AI 审查正向链路
  - `sparse` 模板加入"演示幻觉"标记，让证据对齐的**拦截效果也能演示**

  如果降级路径只能靠临时编造文本，那实际上就是演示不了——
  而"AI 结论无法定位时会被拦下来"恰恰是本项目最值得讲的一点。

## 完成结论

- 状态：**已验收**

### 面试可讲点

1. **怎么防止大模型编造条款？** → 四道闸门：schema 校验（不合法整体丢弃）→ 证据对齐（引文必须能定位回原文）→ 置信度裁决（低于阈值强制人工）→ 状态机（**初始状态没有"已生效"**）。**核心不是让模型更准，而是让它的错误可检测。**
2. **置信度怎么算？** → `模型自评 × 匹配级别权重 × 引文长度惩罚`。只用自评不行，**模型普遍高估自己**；把证据强度乘进去，这个数字才反映"有多值得信"。
3. **引文定位不到的结论怎么处理？** → 标 `EVIDENCE_MISMATCH`、`char_start` 置空、**不进报告正文**，只作为人工待办。读取侧在 SQL 里就过滤掉了，即使有人写新接口忘了过滤，也应该走那个方法。
4. **`riskType` 不在枚举内为什么整条丢弃？** → 静默归类成 `UNKNOWN_RISK` 会掩盖"模型没遵守约定"这个信号，一旦掩盖就再也发现不了提示词漂移。
5. **对齐失败的要素为什么不直接丢掉？** → 丢掉的话，"没有这一行"会同时表示"从没抽过"和"抽过但失败"，人工复核时无法区分。所以落库为 `UNKNOWN` + 原因，只是值为空。
6. **`@ConditionalOnProperty` 有什么坑？** → 我本来用它让两个 `AiClient` 实现互斥，结果两个 bean 同时注册，`NoUniqueBeanDefinitionException` 导致整个上下文起不来。**教训：当"互斥"是硬要求时，用显式分支表达它，不要依赖注解的求值时机**——条件注解的错误方式是"两个都在"或"一个都没有"，都要等启动失败才发现。
7. **删除合同踩过什么坑？** → 新加了要素表却忘了登记到清理段，**孤儿数据不报错、不影响功能**，只在统计上慢慢积累。修复时加了两件事：清理日志把每类行数列出（某类始终为 0 就是漏登记的痕迹），以及一个专门的回归测试——并且**测试先断言"数据确实写进去了"**，否则"删除后为 0"可能是假绿。
8. **为什么要素字段名不叫 `value`？** → 两个原因：H2 里 `VALUE` 是保留字（建表直接失败），以及项目没开下划线转驼峰，列名 `element_value` 与属性 `value` 对不上会导致**写了读不到**——症状是值全为 null 但状态显示正常。

### 新增/变更的接口

- `POST /api/contracts/{id}/extract`、`GET /api/contracts/{id}/elements`
- `POST /api/contracts/{id}/ai-review`、`GET /api/contracts/{id}/ai-findings?reportableOnly=`
- 数据模型：新增 `contract_element`、`ai_finding` 两张表
- 调试台新增「AI 要素抽取与风险审查」面板
- 示例合同新增 `risky` 模板；`sparse` 模板加入"演示幻觉"标记

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [ai-review 模块笔记](../modules/ai-review.md) | 四道闸门、置信度公式 |
| [extract 模块笔记](../modules/extract.md) | 对齐失败仍落库的理由 |
| [02 架构](../02-architecture.md) | D-40~D-44 |
| [README](../../README.md) | 新接口与 `risky` 模板 |

### 后续任务

T-017 / T-018：审查状态机 + 人工复核与**只追加审计**。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(extract): extract elements through the evidence aligner` | T-015 |
| `feat(ai): review contracts with schema checks and evidence gating` | T-016 |
| `fix(ai): pick the client in an explicit config instead of a condition` | 缺陷 1 |
| `fix(extract): rename the value column and property to element_value` | 缺陷 2 |
| `fix(parse): cascade deletion to elements and ai findings` | 缺陷 3 |
| `feat(web): add the AI extraction and review panel to the debug console` | 演示 |
| `feat(web): add a risk-bearing sample contract for demos` | 演示素材 |

%%
本卡已完成验收。四道闸门与置信度公式已回写 ai-review 模块笔记。
终端的原始输出未长期保存。
%%
