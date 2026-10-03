---
title: T-019 AI 不可用降级
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-019 AI 不可用降级

## 任务边界

- 所属模块：[workflow](../modules/workflow.md)、[ai-review](../modules/ai-review.md)
- 对应需求/验收：**F-06 / A-08**、不变式 **I-04**
- 目标行为：
  - AI 不可用时，**规则结论与人工复核流程完全不受影响**
  - 降级是**可复现、可演示**的状态，而不是靠运气触发的故障
  - 降级**必须与"未发现风险"可区分**
- 前置依赖：T-017（状态机）、T-018（复核）✅
- 涉及的不变式：**I-04**

> [!important] 这是三个「绝不砍」项的最后一个
> 反例非常容易写出来：把 AI 失败当成任务失败，
> 于是"**AI 挂了整份审查就废了**"。
> 那样规则引擎做得再准也没有意义——用户根本走不到看结论那一步。

## 为什么需要一个"不可用通道"

这是本任务最先遇到的现实问题：**降级路径没法演示。**

| 方案 | 为什么不行 |
| --- | --- |
| 用 Mock 桩 | 它设计成"永远可成功"，**正好掩盖了降级路径** |
| 不配 API Key | `AiClientConfig` 会回退到 Mock，不会抛 `AI_UNAVAILABLE` |
| 拔网线 / 耗尽额度 | **不可复现**，演示现场会翻车 |

于是显式提供 `AI_CLIENT_MODE=unavailable`：

| `client-mode` | `enabled` | 装配的实现 | 用途 |
| --- | --- | --- | --- |
| `auto`（默认） | `false` | `MockAiClient` | 开发、测试、断网演示，零额度消耗 |
| `auto` | `true` | `DeepSeekClient` | 真实调用 |
| **`unavailable`** | 任意 | **`UnavailableAiClient`** | **确定性地演示与验收降级** |

它的失败消息刻意写明"这是显式开关，不是故障"——
否则排查会浪费大量时间在一个"故意"的状态上。

## 验收示例（六项，全部有测试与端到端证据）

| # | 命题 | 期望 |
| --- | --- | --- |
| 1 | AI 挂时规则仍能判定 | 四条规则**全部给出确定结论**，`undetermined = 0` |
| 2 | AI 调用明确失败 | HTTP **503** + `code=AI_UNAVAILABLE`，不是笼统的 500 |
| 3 | 任务降级但**不被伪装** | 状态 `AI_UNAVAILABLE`（**不是** `AWAITING_REVIEW`） |
| 4 | 降级不是终点 | 规则结论仍在（4 条），`proceed` 后可进入人工复核 |
| 5 | 无 AI 结论时人工仍可工作 | `CONFIRM_NO_RISK` 可记录，哈希链完好 |
| 6 | **降级 ≠ 未发现风险** | `aiAvailable=false`、状态、原因、消息四处都可区分 |

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `client/UnavailableAiClient.java` | 显式的不可用通道 |
| `client/AiClientConfig.java` | 三通道装配 |
| `web/GlobalExceptionHandler` | **`AiCallException` → 503/429/502 映射** |
| `aireview/AiInvocationRunner.java` | **把 AI 调用放进独立事务（REQUIRES_NEW）** |
| `review/ReviewTaskService.java` | 降级态与 `proceedAfterDegrade` |

## TDD 记录

| 验证项 | 结果 |
| --- | --- |
| 降级集成测试 | ✅ `AiDegradationTest` 9 例 |
| 异常映射测试 | ✅ `AiExceptionMappingTest` 5 例（含"每个错误码都不许落到 500"） |
| **合计** | ✅ `Tests run: 272, Failures: 0, Errors: 0` / BUILD SUCCESS |
| **真实 MySQL 端到端** | ✅ 六项命题全部通过（见上表） |

## 实施日志（短期）

本任务抓到 **3 个 bug，其中 2 个是真问题，而且都只在真实 HTTP 调用下才暴露。**

### 缺陷 1：降级被后续迁移悄悄覆盖

`start()` 里我先 `moveTo(AI_UNAVAILABLE)`，然后**无条件**再 `moveTo(AWAITING_REVIEW)`。

结果：**降级状态被覆盖**，任务看起来像"审查正常完成"，
只在 `status_reason` 里留了一句话。那份记录在列表里与正常完成**无法区分**——
用户会以为"审查完成，未发现风险"，而实际是"AI 根本没跑"。

**这正是 I-04 要防的误导。** 修复：降级时**必须停在** `AI_UNAVAILABLE`，
要继续走人工复核必须显式调用 `proceed`（一个可观察的动作）。

> 这个 bug 是我写的第 6 条断言（"降级不能被伪装成未发现风险"）抓到的。

### 缺陷 2：`AiCallException` 没有异常映射 → 500

不显式处理时它落到兜底分支，返回通用的 `500 INTERNAL_ERROR`。
**客户端无法区分"AI 不可用（可降级继续）"与"服务端崩了"**，
而这两者对使用者的意义完全不同。

修复：显式映射，并且**把错误码原样返回**，另加 `degradable` / `retryable` 两个布尔：

| 错误码 | HTTP | 可降级 | 可重试 |
| --- | --- | --- | --- |
| `AI_UNAVAILABLE` | 503 | ✅ | ❌ |
| `BUDGET_EXCEEDED` / `CALL_LIMIT_EXCEEDED` | 429 | ✅ | ❌ |
| `AI_TIMEOUT` / `AI_RATE_LIMITED` / `AI_SERVER_ERROR` | 502 | ❌ | ✅ |
| `SCHEMA_INVALID` | 502 | ❌ | ❌ |

### 缺陷 3（最隐蔽）：`REQUIRES_NEW` —— 单元测试里完全看不出来

**症状**：单元测试全绿，但通过真实 HTTP 调用启动任务时返回 500：

```
UnexpectedRollbackException: Transaction rolled back because it
has been marked as rollback-only
```

**根因**：`extract()` / `review()` 各自带 `@Transactional`。它们抛
`AiCallException` 时，Spring 把**当前事务**标记为 rollback-only。
调用方即使 `catch` 住了并继续，**提交时依然整体回滚**——标记一旦打上就不会撤销。

后果：
- 规则校验白跑了
- 已写入的 `review_task` 记录被回滚
- 客户端拿到 500 而不是"已降级"

**为什么单元测试发现不了**：测试方法自身的 `@Transactional`
让一切都在一个大事务里，**回滚边界被测试框架掩盖了**。
只有真实 HTTP 调用（每个请求一个事务）才暴露。

**修复**：把 AI 调用包在 `REQUIRES_NEW` 独立事务里（`AiInvocationRunner`）。
语义上也更正确：**AI 调用的失败是一个独立事件，不该把"任务已创建"一起否定掉。**

**连带发现**：改用 `REQUIRES_NEW` 后，两个测试类失败了——因为独立事务
只能看到**已提交**的数据，而测试自己的事务让数据处于未提交状态。
这两个类改为**非事务**（测试要反映真实事务边界，就不该用一个大事务把一切包住），
代价是数据会真实落库，用 `@AfterEach` + `JdbcTemplate` 清理。

> **教训：`@Transactional` 的单元测试会掩盖事务边界问题。**
> 涉及多事务协作的路径，必须至少有一次真实 HTTP 层的验证。

## 完成结论

- 状态：**已验收**

### 面试可讲点

1. **AI 挂了系统还能用吗？** → 能。这是刻意的设计：AI 不可用时任务进入 `AI_UNAVAILABLE`（**不是终态**），规则结论与要素抽取都还在，人工可以继续复核。**如果把 AI 失败当成任务失败，规则引擎做得再准也没有意义——用户根本走不到看结论那一步。**
2. **怎么保证降级不被伪装成"没问题"？** → 四处都可区分：状态是 `AI_UNAVAILABLE` 而不是 `AWAITING_REVIEW`；`aiAvailable=false`；`statusReason` 必须写明原因；响应消息不含"未发现风险"。**这个 bug 我真的写出来过**——降级被后续的状态迁移覆盖了，那份记录在列表里与正常完成无法区分。
3. **降级路径怎么演示？** → Mock 桩设计成"永远可成功"，**正好掩盖了降级**；不配 Key 会回退到 Mock；拔网线不可复现。所以我显式加了 `AI_CLIENT_MODE=unavailable` 通道，让降级变成**确定性的**。**演示素材必须能触发要演示的那条路径。**
4. **AI 调用失败为什么不能让整个事务回滚？** → 这是我踩的最隐蔽的坑。`extract`/`review` 带 `@Transactional`，抛异常时 Spring 把当前事务标记为 rollback-only，**调用方 catch 住也没用，提交时照样整体回滚**。修复是把 AI 调用放进 `REQUIRES_NEW` 独立事务。**语义上也更对：AI 失败是独立事件，不该否定"任务已创建"这个事实。**
5. **这个 bug 为什么单元测试没抓到？** → 测试方法自身的 `@Transactional` 让一切在一个大事务里，**回滚边界被测试框架掩盖了**。只有真实 HTTP 调用才暴露。**教训：涉及多事务协作的路径，必须至少有一次真实 HTTP 层验证。**
6. **AI 错误怎么映射成 HTTP 状态码？** → `AI_UNAVAILABLE` → 503（服务暂时不可用，系统本身是好的）；限额类 → 429（调用方需等待或调整）；超时/限流/5xx/schema 非法 → 502（上游出问题）。并且额外返回 `degradable` 与 `retryable` 两个布尔，**让客户端不必自己推断该怎么办**。有一条测试穷举所有错误码，确保**没有任何一类落到兜底的 500**。
7. **schema 非法为什么不可重试？** → 同样的提示词会得到同样的坏输出，重试只是浪费额度。

### 新增接口与配置

- 配置：`AI_CLIENT_MODE`（`auto` / `unavailable`）
- 异常映射：`AiCallException` → 503 / 429 / 502，响应含 `degradable` 与 `retryable`

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [workflow 模块笔记](../modules/workflow.md) | 降级通道、事务边界 |
| [02 架构](../02-architecture.md) | D-56~D-59 |
| [README](../../README.md) | 三通道表、降级演示方式 |
| [PROGRESS](../PROGRESS.md) | 三个新踩坑 |

### 后续任务

Day 5：一键演示脚本、README 定稿、面试脚本。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(ai): add an explicitly unavailable channel to demonstrate degradation` | 不可用通道 |
| `fix(review): keep degraded tasks degraded instead of advancing them silently` | 缺陷 1 |
| `fix(web): map AI call failures to explicit status codes and a degradable flag` | 缺陷 2 |
| `fix(ai): run model calls in their own transaction so degradation can commit` | 缺陷 3 |

%%
本卡已完成验收。降级通道与事务边界已回写 workflow 模块笔记。
终端的原始输出未长期保存。
%%
