---
title: T-014 DeepSeek 客户端 · Mock 开关 · 成本上限
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-014 DeepSeek 客户端 · Mock 开关 · 成本上限

## 任务边界

- 所属模块：[ai-review](../modules/ai-review.md)
- 对应需求/验收：F-05 的基础、**A-08**（AI 不可用可降级）、不变式 **I-04**
- 目标行为：
  - 能用 `RestClient` 直连 DeepSeek（OpenAI 兼容接口）
  - **默认走 Mock 桩**：开发/测试零额度消耗，断网也能演示
  - **调用次数与预算有硬上限**，超限直接失败
  - 错误分类明确，只对可重试的错误重试（最多 1 次）
- 本任务不做：要素抽取与风险审查的业务逻辑（T-015 / T-016）
- 前置依赖：T-013 ✅
- 涉及的不变式：**I-04**（AI 不可用时规则链仍可用）

## 为什么自写客户端而不用 Spring AI（决策 D-07）

只有 DeepSeek 一个供应商，"屏蔽供应商差异"这个最大卖点价值为零。
自写客户端约 100 行、没有版本不确定性、供应商耦合集中在一个类里。
**更关键的是可测试性**：把调用封在 `AiClient` 接口后面，
测试注入桩就能覆盖超时、限流、非法 JSON 三类分支。

## 一个设计同时解决两件事

`app.ai.enabled` 这一个开关既是**测试开关**，也是**演示降级开关**：

| 取值 | 行为 | 用途 |
| --- | --- | --- |
| `false`（默认） | 注入 `MockAiClient` | 开发、测试、断网演示、**零额度消耗** |
| `true` | 注入 `DeepSeekClient` | 真实调用，受成本守卫限制 |

两个实现用 `@ConditionalOnProperty` 互斥，**而不是 `@Primary` 挑一个**：
两个同类型 bean 同时存在时，注入会依赖 bean 名称与顺序，
那种"能跑但说不清"的状态在排查时非常费劲。

## 成本守卫：真正的风险不是额度不够，而是调试烧光

15 元额度按正常演示只需要不到 1 元。真实的失败方式是这样发生的：

> 缓存没生效 → 跑一次集成测试 20 次调用 → 跑十遍 200 次 → 额度见底。

因此两道硬上限：

| 上限 | 作用 |
| --- | --- |
| 单份合同调用次数上限 | 超限**直接失败**，让问题在第一次异常调用时就暴露 |
| 日预算上限 | 把 token 数换算成估算金额累计，超支前刹住车 |

**Mock 模式下不限额**：桩调用不花钱，限制它只会妨碍开发。

> ⚠️ 计数在内存里。**多实例部署必须换成 Redis 计数**，
> 否则每个实例各算一份，限额形同虚设。这是已知限制。

## Mock 桩的关键设计

**桩不返回写死的 JSON，而是从输入文本里截取真实片段。**

这一点很重要：如果桩返回固定内容（例如固定的"乙方承担一切损失"），
那么证据对齐会（**正确地**）把它判为"原文中不存在"从而降级——
**测试与演示看到的全是降级状态，正向链路完全测不到**。

所以桩的做法是：找出含风险关键词的句子，把**整句原文**作为 `quote` 返回。
这样对齐必然是 `EXACT`，而"风险判断逻辑"本身由桩的固定规则给出。

这条要求由一个专门的测试固化下来：遍历桩产出的每一条引文，
逐一交给**真实的** `EvidenceAligner`，要求全部命中。

桩还内置了一条"演示幻觉"样本（仅在文本含"演示幻觉"时触发），
用于在演示时展示降级路径——**降级路径也要能演示，不能只存在于文档里**。

## 验收示例

| 情况 | 期望 |
| --- | --- |
| `enabled=false` | 抛 `AI_UNAVAILABLE`（**降级状态而非错误**），且不发任何请求 |
| `enabled=true` 但无 Key | 同样 `AI_UNAVAILABLE`，**不发无凭据的请求** |
| HTTP 429 | `AI_RATE_LIMITED`，重试 1 次，共 2 次请求 |
| HTTP 5xx | `AI_SERVER_ERROR`，可重试 |
| 读超时 | `AI_TIMEOUT` |
| 响应缺 `choices` | `SCHEMA_INVALID`，**不重试**（同样的提示词会得到同样的坏输出） |
| 响应非 JSON / 空体 / content 为空 | 均 `SCHEMA_INVALID`，**不返回空串让上层去猜** |
| 单合同调用超限 | `CALL_LIMIT_EXCEEDED`，**不再发出请求** |
| 日预算耗尽 | `BUDGET_EXCEEDED`，**明确失败，绝不降级成"未发现风险"** |

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `client/AiClient.java` | 客户端抽象，存在的唯一理由是可测试性 |
| `client/AiErrorCode.java` | 7 种错误分类，按"该做什么"划分 |
| `client/AiCallException.java` | 携带错误码 + `isRetryable()` |
| `client/AiCostGuard.java` | 次数与预算上限、用量估算 |
| `client/DeepSeekClient.java` | RestClient 直连，错误映射与有限重试 |
| `client/MockAiClient.java` | 零消耗桩，**引文取自输入文本** |

## TDD 记录

### 1. 失败测试（RED）

`DeepSeekClientTest`（17 例）用 **MockWebServer 起真实 HTTP 服务端**，
而不是 mock 掉 `AiClient` 接口——mock 我自己的接口只能验证"调用发生过"，
**测不到 HTTP 状态码映射、超时、响应体解析这些真正容易出错的地方**。

`MockAiClientTest`（8 例）验证桩的引文能被真实对齐算法命中。

### 2. 最小实现（GREEN）

### 3. 必要整理（REFACTOR）

`MockAiClient` 从 `@Primary` 改为 `@ConditionalOnProperty`（见上）。

### 4. 验证结果

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| 客户端测试 | ✅ | 17 例（含限流/5xx/超时/非法响应/超限拒绝） |
| 桩测试 | ✅ | 8 例（含"每条引文都能被对齐命中"） |
| **合计** | ✅ | `Tests run: 199, Failures: 0, Errors: 0` / BUILD SUCCESS |
| 应用启动 | ✅ | `/api/health` 返回 `aiEnabled=false`，Mock 桩生效 |

## 实施日志（短期）

修了 **1 个真 bug**：

**超时被错标成 `AI_SERVER_ERROR`。**
`SimpleClientHttpRequestFactory` 在读超时时抛的是 `SocketTimeoutException`，
而它**不是** `ResourceAccessException`，会直接落到兜底分支被错标。

后果不只是错误码不准：`AI_SERVER_ERROR` 被标为"可重试"，
而超时重试会再耗一次额度与一个超时周期；
更糟的是排查时"服务端错误"会把注意力引向对方服务，而真实原因是本地超时配置。

修复：加 `isTimeout()` 递归遍历异常链（包装层次因 HTTP 客户端实现而异，
不能只看最外层）。

此外修正 1 处编译问题：`MockResponse.setBodyDelay` 的签名是
`(long, TimeUnit)`，没有 `Duration` 重载。

## 完成结论

- 状态：**已验收**

### 面试可讲点

1. **为什么自写客户端而不用 Spring AI？** → 只有一个供应商，抽象层的最大卖点（屏蔽差异）价值为零。自写约 100 行、无版本不确定性，**而且把调用封在一个接口后面就获得了可测试性**——测试注入桩即可覆盖超时/限流/非法 JSON。
2. **一个开关同时做两件事？** → `app.ai.enabled=false` 既是测试开关也是演示降级开关。**一个设计解决两个问题**，这是它比引入框架更划算的地方。
3. **AI 额度只有 15 元，你怎么防烧光？** → 真正的风险不是额度不够，而是**调试时的循环调用**：缓存没生效 → 跑一次集成测试 20 次调用 → 跑十遍 200 次。因此设了单合同次数上限与日预算上限，**超限直接失败**而不是继续调，让问题在第一次异常调用时就暴露。
4. **为什么 Mock 模式下不限额？** → 桩调用不花钱，限制它只会妨碍开发。**限额的目的是控成本，不是控行为。**
5. **为什么要用 MockWebServer 而不是 mock 接口？** → mock 我自己的接口只能验证"调用发生过"，**测不到 HTTP 状态码映射、超时、响应体解析**这些真正容易出错的地方。MockWebServer 起真实端口，让被测代码走完整 HTTP 路径。
6. **schema 非法为什么不重试？** → 同样的提示词会得到同样的坏输出，重试只是浪费额度。所以 `isRetryable()` 只对超时/限流/5xx 返回 true。
7. **超时踩过什么坑？** → 超时异常不是 `ResourceAccessException`，一开始落进兜底分支被错标成"服务端错误"。**错误码不准只是表象**——它还被标成"可重试"，会多耗额度和一个超时周期，而且排查方向会被引向对方服务。

### 新增/变更的错误码

`AI_UNAVAILABLE`、`AI_TIMEOUT`、`AI_RATE_LIMITED`、`AI_SERVER_ERROR`、`SCHEMA_INVALID`、`CALL_LIMIT_EXCEEDED`、`BUDGET_EXCEEDED`

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [ai-review 模块笔记](../modules/ai-review.md) | 开关设计、成本守卫、桩的引文要求 |
| [02 架构](../02-architecture.md) | D-35~D-37 |
| [README](../../README.md) | 额度保护说明与切换方式 |

### 后续任务

T-015 / T-016：把要素抽取与 AI 风险审查接到证据对齐上。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(ai): add DeepSeek client with mock switch and cost guard` | T-014 主体 |
| `fix(ai): map socket timeouts to AI_TIMEOUT instead of server error` | 真 bug |
| `test(ai): require every mock quote to align against real text` | 固化桩的设计要求 |

%%
本卡已完成验收。开关与成本守卫的设计已回写 ai-review 模块笔记。
终端的原始输出未长期保存。
%%
