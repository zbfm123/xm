---
title: T-018 人工复核与只追加审计
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-018 人工复核与只追加审计

## 任务边界

- 所属模块：[workflow](../modules/workflow.md)
- 对应需求/验收：**F-07 / A-07 / A-09**、不变式 **I-06**
- 目标行为：
  - 人工复核动作被完整记录：谁、何时、对哪条结论、做了什么、为什么
  - 记录**只追加**：不能修改、不能删除
  - 记录**可自证未被改动**（哈希链）
  - 审计痕迹**不随合同删除而消失**
- 前置依赖：T-016（AI 结论）✅
- 涉及的不变式：**I-06**（审计只追加）

> [!important] 为什么这是三个「绝不砍」项之一
> 这是本项目区别于"AI 套壳"的第三个支点：
> **AI 给候选，人做决定，决定被永久留痕。**
> 没有这一层，系统只能说"AI 认为有风险"，说不清"谁最终认定有风险"。

## 三重保障

「只追加」不能只靠一句约定或一个注释。本任务用三层来保证：

| 层 | 手段 | 防住什么 |
| --- | --- | --- |
| 1. 接口 | `ReviewActionMapper` **只有 insert 与 select** | 应用代码里想改也找不到方法 |
| 2. 数据库 | `BEFORE UPDATE` / `BEFORE DELETE` **触发器** | 手工 SQL、运维脚本、将来新同事的代码 |
| 3. 密码学 | **哈希链** | 绕过前两层的人（有 root 权限）——改动可被检测 |

第 1 层由 `AppendOnlyAuditContractTest` 用**反射扫描**强制：
一旦有人加了 update/delete 方法，构建就失败。
**注释不会阻止任何人，构建失败会。**

第 2 层的触发器用 `SIGNAL SQLSTATE '45000'` 抛错，
而不是静默忽略——静默忽略会让"改失败了"看起来像"改成功了"。

## 哈希链

```
record_hash = SHA-256(
    previous_hash | idempotency_key | contract_id | finding_id
  | action | reason | operator_id | operator_name
  | previous_status | new_status )
```

- 链首的 `previous_hash` 是 **64 个 0**，不是 null
  （null 会让"链首"与"字段缺失"混在一起）
- 字段之间用 `\u0001` 分隔：它对正常文本几乎不可能出现，
  避免"字段内容挪位后拼出的字符串相同"这种边界伪造
- 校验时同时检查两件事：内容哈希是否对得上、前后链是否接得上

### ⚠️ 它能防什么、不能防什么

**能防**：事后修改某条记录的内容或删除中间某条。

**不能防**：知道算法与字段顺序的人重算整条链，伪造一个自洽的历史。
真正的不可抵赖需要**外部时间戳或数字签名**（由第三方持有私钥）。

**这是已知限制，必须在面试时主动说明，不能宣称"不可篡改"。**

## 验收示例

| 情况 | 期望 |
| --- | --- |
| 记录 ACCEPT | 状态 `PENDING → ACCEPTED`，记录含前后状态 |
| 同一幂等键重放 | **返回既有记录，不写第二条** |
| 缺幂等键 | 直接拒绝（审计写错后无法删除，重复必须在入口挡住） |
| ACCEPT 不给 `findingId` | 拒绝（否则报告会出现无法追溯的"已采纳"） |
| `CONFIRM_NO_RISK` | 合同级动作，允许 `findingId` 为空 |
| 改动一条记录的 `reason` | `intact=false`，**指出是哪一条** |
| 删除中间一条记录 | `intact=false`，报"前驱哈希不匹配" |
| 伪造一条重算过自身哈希的记录 | **仍被检测到**（其后记录的 `previous_hash` 对不上） |
| 没有复核记录 | `intact=true, checked=0`（不是"校验失败"） |
| 删除合同 | **审计记录仍保留**（刻意设计，有测试锁定） |
| MySQL 直接 UPDATE / DELETE | `ERROR 1644 (45000)` 被拒绝 |

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `review/domain/ReviewActionType.java` | 5 种动作，含独立的 `CONFIRM_NO_RISK` |
| `review/domain/ReviewActionRow.java` | 行对象（**为什么可变见下**） |
| `review/audit/ReviewActionHasher.java` | 哈希计算 + `ChainVerification` |
| `review/mapper/ReviewActionMapper.java` | **只有 insert / find** |
| `review/ReviewActionService.java` | 记录、幂等、状态推进、链校验 |
| `review/ReviewActionController.java` | `POST /reviews`、`GET /reviews`、`GET /reviews/verify` |
| `db/schema.sql` | 表 + 两个触发器 |

## TDD 记录

### 验证结果

| 验证项 | 结果 |
| --- | --- |
| 契约测试（反射） | ✅ `AppendOnlyAuditContractTest` 3 例 |
| 集成测试 | ✅ `ReviewActionIntegrationTest` 13 例 |
| **合计** | ✅ `Tests run: 229, Failures: 0, Errors: 0` / BUILD SUCCESS |
| **MySQL 触发器** | ✅ UPDATE / DELETE 均被 `ERROR 1644 (45000)` 拒绝 |
| **真实 MySQL 端到端** | ✅ 见下表 |

#### 真实 MySQL 端到端结果

| 场景 | 结果 |
| --- | --- |
| 采纳第一条 | `PENDING => ACCEPTED`，操作人"法务主管（演示）" |
| 驳回第二条 | `PENDING => REJECTED` |
| **链式相连** | 第 2 条的 `previousHash` **等于**第 1 条的 `recordHash` |
| **幂等重放** | 返回同一条 id，库中复核记录**仍为 2 条** |
| ACCEPT 不给 findingId | 400，消息明确指出原因 |
| 哈希链校验 | `intact=true checked=2` |
| **数据库触发器** | UPDATE / DELETE 均被拒绝，数据完好无损 |
| **删除合同** | 审计记录 **2 → 2**（正文/要素/AI 结论已清理） |

## 实施日志（短期）

本任务踩了 **3 个坑，全部与"不可变类 + MyBatis"有关**，很有教育意义。

### 缺陷 1：不可变类触发 MyBatis 的「按列顺序构造器映射」

我最初把 `ReviewActionRow` 写成不可变类：字段 `final`、只有全参构造函数、无 setter。

MyBatis 遇到**只有一个构造函数**的返回类型时，会启用
`applyColumnOrderBasedConstructorAutomapping`——把第 N 列塞给第 N 个构造参数。

症状极具误导性：

```
Error attempting to get column 'reason' from result set.
Cause: Data conversion error converting "理由0"
    at LongTypeHandler.getNullableResult
```

**看起来像中文编码问题**（错误里中文是乱码，且只有 `reason` 这一列报错），
**实际上是 MyBatis 拿 `reason`（字符串）去填构造参数里的某个 `Long`。**

我依次试了三条路，**全都拦不住它**：

1. 写完整的 `@Results` —— 仍触发自动映射
2. `@ResultType` 指定手写 `TypeHandler` —— 未被采用
3. `@AutomapConstructor` —— 仍然按列顺序

最后改成可变行对象（无参构造 + setter），问题立刻消失。

> **这个让步是经过权衡的，而且我认为让步是对的：**
> **不变式的保障本来就不该靠 Java 的 `final` 字段。**
>
> `final` 只能防住"通过这个类的方法改动"，
> 而任何人都能直接写 SQL 绕过去。真正的保障是
> 「没有改的入口」+「数据库触发器」+「哈希链」——
> **把成本花在真正有效的那一层上。**

### 缺陷 2：MyBatis 一级缓存让篡改检测形同虚设

这个 bug **比第一个严重得多**。

测试现象：用 `JdbcTemplate` 改了一条记录的 `reason`，然后校验哈希链——
**却报告 `intact=true`**。但直接查库，值确实已经改了。

根因：`ReviewActionService.record()` 在**同一个事务**里先 `findLast()` 读了一次，
MyBatis 的**一级缓存（SqlSession 作用域）**把结果缓存了下来。
之后 `verifyChain()` 再读，命中的是**缓存**而不是数据库。

**后果**：`verifyChain` 在刚写过记录的同一事务里调用，可能校验的是缓存快照——
**篡改检测会给出"完好"的错误结论。**
一个安全机制悄悄失效，而且它失效的方式是"说一切正常"。

修复：给审计表的所有 SELECT 加
`@Options(flushCache = FlushCachePolicy.TRUE, useCache = false)`，
**不缓存审计记录**。

> **教训：只追加的审计数据在读的时候不能走缓存。**
> 缓存的前提是"数据不变"，而审计表恰恰是最需要"读到最新真相"的表。

### 缺陷 3：H2 里 `ACTION` 是保留字

与 T-015 的 `VALUE` 同一类问题。建表用 `action_code`，读取时 `action_code AS action`。

## 完成结论

- 状态：**已验收**

### 面试可讲点

1. **为什么要做只追加审计？** → 系统里"AI 说有风险"和"人认定有风险"是两件事。没有审计层，报告里的一句"存在风险"**追溯不到是谁认定的**。这一层让每个决定都能回答：谁、何时、基于什么理由、改了什么状态。
2. **"只追加"怎么保证？** → 三层，而且一层比一层硬：Mapper 里**没有** update/delete 方法（有反射测试强制）→ 数据库 `BEFORE UPDATE/DELETE` **触发器**（手工 SQL 也改不动）→ **哈希链**（绕过前两层也能被检测出来）。**注释不会阻止任何人，构建失败会。**
3. **为什么不用 Java 的 `final` 字段来表达不可变？** → 我一开始就是这么写的，结果触发了 MyBatis 的按列顺序构造器映射，报出一个看起来像中文编码问题的错误。**更关键的是我意识到：`final` 只能防住"通过这个类的方法改动"，而任何人都能直接写 SQL 绕过去。** 保障应该放在真正有效的那一层。
4. **哈希链能防篡改吗？** → 能防"事后改动"，**不能防"抵赖"**。知道算法和字段顺序的人可以重算整条链伪造一个自洽的历史。真正的不可抵赖需要外部时间戳或数字签名。**这是已知限制，我会主动说明，不会宣称"不可篡改"。**
5. **踩过最隐蔽的 bug 是什么？** → MyBatis 的**一级缓存**让篡改检测失效：`record()` 刚读完链尾，`verifyChain()` 再读时命中缓存，于是**报"链路完好"，而库里其实已经被改了**。一个安全机制失效的方式是"说一切正常"，这最难发现。修复是给审计表的 SELECT 关掉缓存——**缓存的前提是数据不变，而审计表恰恰最需要读到最新真相。**
6. **为什么要幂等键？** → 审计日志写错后**无法删除**，所以重复记录必须在入口挡住，不能靠事后清理。网络重试和用户双击都会产生重复动作。
7. **为什么 `CONFIRM_NO_RISK` 是独立动作？** → 因为"**没有人看**"和"**看过了且确认无风险**"必须能区分。否则报告里一句"无风险"无法追溯到底是谁认定的。
8. **为什么 ACCEPT 必须指向具体结论？** → 允许一个不指向任何结论的 ACCEPT，报告里就会出现无法追溯的"已采纳"——它对应哪一条风险？没人知道。

### 新增接口

- `POST /api/contracts/{id}/reviews` —— 记录复核动作（幂等）
- `GET /api/contracts/{id}/reviews` —— 复核历史
- `GET /api/contracts/{id}/reviews/verify` —— **哈希链完整性校验**

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [workflow 模块笔记](../modules/workflow.md) | 三重保障、哈希链边界 |
| [02 架构](../02-architecture.md) | D-48~D-51 |
| [README](../../README.md) | 复核接口与只追加说明 |

### 后续任务

T-017（审查状态机）、T-019（AI 不可用降级作为独立验收项）。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(review): record human review actions with an append-only audit trail` | T-018 主体 |
| `test(review): enforce the append-only contract by reflection` | 反射强制 |
| `fix(review): use a mutable row so MyBatis stops mapping by column order` | 缺陷 1 |
| `fix(review): disable the local cache so tampering cannot hide behind it` | 缺陷 2 |

%%
本卡已完成验收。三重保障与哈希链边界已回写 workflow 模块笔记。
终端的原始输出未长期保存。
%%
