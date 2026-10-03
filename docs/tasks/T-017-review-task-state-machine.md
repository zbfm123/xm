---
title: T-017 审查任务状态机与幂等
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-017 审查任务状态机与幂等

## 任务边界

- 所属模块：[workflow](../modules/workflow.md)
- 对应需求/验收：**F-06**、**A-04/A-08 的基础**
- 目标行为：
  - 把规则校验 → 要素抽取 → AI 审查 → 人工复核串成一条可重入的流程
  - **重复提交返回同一 taskId**
  - **合法迁移有穷举单测**
  - AI 不可用时降级但不终止（I-04）
- 前置依赖：T-012（规则）、T-016（AI 结论）✅
- 涉及的不变式：**I-04**（AI 不可用可降级）、**I-05**（幂等）

## 状态机

```
PENDING ──→ IN_PROGRESS ──→ AWAITING_REVIEW ──→ COMPLETED
                 │                  ↑
                 └──→ AI_UNAVAILABLE ┘
```

| 迁移 | 含义 |
| --- | --- |
| `PENDING → IN_PROGRESS` | 开始处理 |
| `IN_PROGRESS → AWAITING_REVIEW` | 机器分析完成，等人工复核 |
| `IN_PROGRESS → AI_UNAVAILABLE` | AI 通道不可用，降级 |
| **`AI_UNAVAILABLE → AWAITING_REVIEW`** | **降级后继续走人工复核** |
| `AWAITING_REVIEW → COMPLETED` | 全部条目复核完毕 |
| 任意非终态 `→ CANCELLED` | 用户取消 |

### 两条最关键的设计

**1. `AI_UNAVAILABLE` 不是终态。**

如果把它设成失败终态，就等于"**AI 挂了整份审查就废了**"——直接违反 I-04。
降级后规则结论与要素抽取的结果都还在，人工可以继续工作。

**2. `CANCELLED` 不能从终态进入。**

"已完成"不能变成可撤销，否则与"审计只追加"的精神冲突。

## 为什么需要独立的任务表

而不是复用 `contract.status`：

| 理由 | 说明 |
| --- | --- |
| **可重入** | 同一份合同可以有多次审查（改判后重跑），任务表保留每次历史 |
| **幂等键** | 重复提交要返回同一 taskId，需要一个稳定的唯一键，`contract` 上放不下 |
| **职责不同** | `contract.status` 描述**数据**状态（已解析/已校验），`task.status` 描述**流程**状态（处理中/待人工复核） |

## 验收示例

| 情况 | 期望 |
| --- | --- |
| 正常启动 | 走到 `AWAITING_REVIEW`，规则与 AI 结论都已产出 |
| 同一幂等键重复提交 | **返回同一 taskId**，且不重跑流程 |
| 不同幂等键 | 产生不同任务，任务表保留多次审查历史 |
| 缺幂等键 | 直接拒绝 |
| AI 不可用 | 任务停在 `AI_UNAVAILABLE`，**`proceed` 可继续进入人工复核** |
| 只复核一部分 | 仍是 `AWAITING_REVIEW`，**不提前完成** |
| 全部复核完 | 推进到 `COMPLETED` |
| 非法迁移 | 抛异常，**状态不变**（不"尽力而为地改一个相近状态"） |
| 跨租户 | 读不到、启动不了 |
| 删除合同 | 任务表清理，**但复核审计保留**（两者性质不同） |

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `review/domain/ReviewTaskStatus.java` | **显式迁移表** + `isTerminal` / `canMoveTo` / `allowedTargets` |
| `review/domain/ReviewTaskRow.java` | 行对象 |
| `review/mapper/ReviewTaskMapper.java` | 任务持久化（**允许 update**） |
| `review/ReviewTaskService.java` | 流程编排、幂等、降级、进度 |
| `review/ReviewTaskController.java` | 启动 / 查询 / refresh / proceed |

### 为什么任务表允许更新，而审计表不允许

```java
/**
 * 与 review_action 不同，任务表允许更新——
 * 它记录的是"流程当前走到哪里"，而不是"发生过什么"。
 * 把流程当前态塞进只追加表，就会变成"每推进一次状态就追加一条"，
 * 读当前状态要扫全表。
 */
```

**这个区分很重要**：把两类不同性质的数据混在一张表里，两种需求都满足不好。

## TDD 记录

### 验证结果

| 验证项 | 结果 |
| --- | --- |
| **穷举单测** | ✅ `ReviewTaskStatusMachineTest` 17 例（6×6 = 36 个状态对全部枚举） |
| 集成测试 | ✅ `ReviewTaskIntegrationTest` 12 例 |
| **合计** | ✅ `Tests run: 258, Failures: 0, Errors: 0` / BUILD SUCCESS |

### 穷举测试怎么做的

```java
// 期望的合法迁移表刻意与生产代码分开写一遍，否则测试只是复述实现
private static final Set<String> EXPECTED_LEGAL = Set.of(
        "PENDING->IN_PROGRESS", "PENDING->CANCELLED", ...);

@Test
void allStatusPairsShouldMatchExpectedTable() {
    for (ReviewTaskStatus from : values())
        for (ReviewTaskStatus to : values())
            if (from.canMoveTo(to)) actual.add(from + "->" + to);
    assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_LEGAL);
}
```

失败消息会**分别列出"多出来的"与"少掉的"**，便于定位。

另外还断言了几条容易被忽略的性质：
- **不可自环**（否则"重复提交"会被误判为合法推进）
- **终态无出边**
- **不能回到 `PENDING`**（会让已发生的处理被抹掉）

## 实施日志（短期）

### 过度设计了一次（自己的老毛病）

我最初为了让 `review` 包不依赖 `ai-review` 包，抽了一个
`ReviewActionMapperHolder` 接口，里面只有一个 `countReviewedFindings` 方法。

**这是没必要的间接层。** 我在 Lean Mode 里已经记过同一个错误
（当时是把 `InMemoryRedis` 做成了完整实现）。

改成直接注入 `AiFindingMapper` 并加一个 `countReviewed` 查询：
一处 SQL，零新增类型。

> **教训重复了一次：为了让"依赖方向好看"而抽的单方法接口，
> 成本大于收益。用一次 SQL 能解决的事，不要引入一个新接口。**

### 测试断言修正 2 处（代码是对的）

1. 我一开始让 `refreshProgress` 负责把任务从 `AI_UNAVAILABLE`
   救回到 `AWAITING_REVIEW`。这个设计不对——**状态推进应当是显式可观察的动作，
   而不是某个"顺手"操作的副产品**。于是单独加了 `proceedAfterDegrade`。
2. 另一个测试里我手工写入的降级原因文本与生产代码不一致，
   导致 `contains("规则结论")` 断言失败。修正测试数据。

## 完成结论

- 状态：**已验收**

### 面试可讲点

1. **为什么要独立的任务表，而不是复用合同状态？** → 三个理由：合同可以有**多次审查**（改判后重跑），任务表保留每次历史；**幂等键**需要一个稳定唯一键，合同表上放不下；两者**描述的东西不同**——`contract.status` 是数据状态（已解析/已校验），`task.status` 是流程状态（处理中/待人工复核）。
2. **AI 挂了整个审查就废了吗？** → 不是。这是本项目刻意设计的：`AI_UNAVAILABLE` **不是终态**，它有一条边通向 `AWAITING_REVIEW`。降级后规则结论与要素抽取都还在，人工照常工作。**如果把它设成失败终态，就等于"AI 挂了全废"，直接违反 I-04。**
3. **状态机怎么测？** → **穷举 6×6 = 36 个状态对全部断言**，而不是只测几条典型路径。状态机的缺陷几乎总是"某条边漏掉了"或"某条边被意外打开"，只测路径发现不了。而且期望表在测试里**独立写一遍**，否则测试只是复述实现。失败消息会分别列出"多出来的"和"少掉的"。
4. **为什么要断言"不可自环"？** → 否则"重复提交"可能被误判成一次合法的状态推进。这种 bug 很隐蔽：状态值没变，但代码以为推进成功了。
5. **哪些数据该允许更新，哪些不该？** → 任务表**允许**更新（它记录"流程当前走到哪里"），审计表**不允许**（它记录"发生过什么"）。**把两类数据混进一张表，两种需求都满足不好**——例如把流程当前态塞进只追加表，就会变成"每推进一次追加一条"，读当前状态要扫全表。
6. **为什么"取消"不能从终态进入？** → 否则"已完成"变成可撤销，与审计只追加的精神冲突。终态就该是终态。
7. **为什么要断言"不能回到 PENDING"？** → 回到起点意味着"重来一遍"，会让已经发生的处理和审计记录失去意义。

### 新增接口

- `POST /api/contracts/{id}/review-tasks` —— 启动任务（幂等）
- `GET /api/review-tasks/{taskId}` —— 查任务状态
- `GET /api/contracts/{id}/review-tasks` —— 该合同的全部任务
- `POST /api/review-tasks/{taskId}/refresh` —— 刷新进度
- `POST /api/review-tasks/{taskId}/proceed` —— **降级后继续进入人工复核**

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [workflow 模块笔记](../modules/workflow.md) | 状态机、任务表与审计表的区别 |
| [02 架构](../02-architecture.md) | D-52~D-54 |
| [README](../../README.md) | 任务接口 |

### 后续任务

T-019（AI 不可用降级作为独立验收项）、Day 5 一键演示与面试脚本。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(review): add the review task state machine with idempotent starts` | T-017 主体 |
| `test(review): enumerate every state pair in the transition table` | 穷举单测 |
| `feat(review): let degraded tasks resume into manual review` | I-04 的出口 |
| `fix(parse): cascade contract deletion to review tasks but not to audit` | 清理与保留的边界 |

%%
本卡已完成验收。状态机与两类表的区别已回写 workflow 模块笔记。
终端的原始输出未长期保存。
%%
