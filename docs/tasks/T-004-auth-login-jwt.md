---
title: T-004 登录与 JWT 签发校验
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-004 登录与 JWT 签发校验

## 任务边界

- 所属模块：[auth](../modules/auth.md)
- 对应需求/验收：F-01、A-01（认证部分）
- 目标行为：用户能用账号密码登录拿到 JWT；带令牌能访问 `/api/auth/me`；登出后令牌立即失效
- 本任务不做：**租户隔离的持久层强制注入**（那是 T-005）、合同相关接口
- 前置依赖：T-003 ✅
- 涉及的不变式：为 I-01 打基础（上下文机制就位，强制注入在 T-005）

## 验收示例

### 正常路径

- 给定：数据库中已存在 `staff01` / `Demo@2026`
- 当：POST `/api/auth/login`
- 则：返回 200，含 JWT、有效期与用户信息，**且响应体不含密码哈希**

### 异常路径

| 情况 | 期望 |
| --- | --- |
| 密码错误 | 401 `BAD_CREDENTIALS` |
| 账号不存在 | 401 `BAD_CREDENTIALS`（**与密码错误同码**，不泄露账号是否存在） |
| 连续失败 5 次 | 423 `ACCOUNT_LOCKED`，且此后正确密码同样被拒 |
| 不带令牌访问受保护接口 | 401 `UNAUTHENTICATED` |
| 令牌被篡改 | 401 `TOKEN_INVALID` |
| 令牌已过期 | 401 `TOKEN_EXPIRED` |
| 令牌已登出 | 401 `TOKEN_REVOKED` |
| 重复登出 | 204（幂等） |

### 明确不该发生的事

- 响应体中不得出现 `passwordHash`（用 `UserView` 投影保证，并有断言）
- 不得用宽泛 `catch` 把令牌异常吞成"未登录"
- 不得在 ThreadLocal 里残留上下文（线程池复用会导致租户串号）
- 令牌解析失败时不得继续以"匿名"身份访问受保护接口

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `auth/domain/Role.java` | 三个角色 + Spring Security 权限名 |
| `auth/domain/User.java` | 用户实体（`passwordHash` 不出服务端） |
| `auth/domain/CurrentUser.java` | 请求级上下文；`require()` 缺失即抛异常 |
| `auth/domain/IssuedToken.java` | 令牌 + 过期时刻 |
| `auth/mapper/UserMapper.java` | 按用户名/ID 查询、失败计数读写 |
| `auth/jwt/JwtService.java` | 签发与解析，失败区分 `EXPIRED` / `MALFORMED` |
| `auth/TokenBlacklist.java` | 登出黑名单，**TTL = 令牌剩余有效期** |
| `auth/LoginAttemptGuard.java` | 失败计数与锁定，**落库而非只放 Redis** |
| `auth/AuthService.java` | 登录/登出/当前用户 |
| `auth/AuthController.java` | `POST /login`、`POST /logout`、`GET /me` |
| `auth/dto/*.java` | 请求与响应投影 |
| `security/JwtAuthenticationFilter.java` | 校验令牌、填充上下文、**finally 清理** |
| `security/RestAuthenticationEntryPoint.java` | 统一 401 响应格式 |
| `security/SecurityConfig.java` | 白名单 + 默认拒绝 + 无状态 |
| `web/GlobalExceptionHandler.java` | 统一错误响应格式 |
| `support/RedisTestConfig.java` | 测试用内存 Redis（带 TTL） |

## TDD 记录

### 1. 失败测试（RED）

- 单元测试 `JwtServiceTest`（10 个用例）：先写"过期令牌必须归类为 EXPIRED"，此时 `JwtService` 尚不存在。
- 集成测试 `AuthIntegrationTest`（13 个用例）：先写"登录成功且响应不含密码哈希"。
- 首次失败原因：类不存在 / 端点未实现。

### 2. 最小实现（GREEN）

- 按上表逐个类实现，未提前实现 T-005 的持久层拦截器。

### 3. 必要整理（REFACTOR）

**这里发生了一次真实的回退，值得记录：**

最初为了让测试不依赖 Redis，我写了一个实现 `RedisConnection` 全接口的内存替身（`InMemoryRedis`），
结果编译报出 20 多处"未实现抽象方法"。而本项目实际只用到两个 Redis 操作：
`set(key, value, ttl)` 与 `hasKey(key)`。

改为用 Mockito 代理这三个方法（含 `opsForValue()`），60 行解决，且 TTL 行为仍然是真实的。

> **教训：为两个操作去实现一整套连接层，是典型的过度设计。**
> 这与项目文档里 Lean Mode 的三问一脉相承——"不写这段代码的代价是什么"。
> 删除的方案已记录在 Git 提交历史中。

### 4. 验证结果

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| 单元测试 | ✅ 通过 | `JwtServiceTest` 10/10 |
| 集成测试（H2 + 内存 Redis） | ✅ 通过 | `AuthIntegrationTest` 13/13 |
| 骨架自检 | ✅ 通过 | `ContractReviewApplicationTests` 2/2 |
| **合计** | ✅ | **Tests run: 25, Failures: 0, Errors: 0 / BUILD SUCCESS** |
| **真实 MySQL 端到端** | ✅ | 11 项 HTTP 验证全部符合预期（见下） |

#### 真实 MySQL 端到端验证结果

> H2 通过不等于 MySQL 通过，因此建表与接口都在真实实例上复验一次。

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | `staff01` / `Demo@2026` 登录 | 200，角色 `LEGAL_STAFF`，租户 1，`expiresIn=7200`，**响应不含密码哈希** |
| 2 | 错误口令 | 401 `BAD_CREDENTIALS` |
| 3 | 账号不存在 | 401 `BAD_CREDENTIALS`（**与错误口令同码**） |
| 4 | 带令牌 `/me` | 200，`displayName='法务专员（演示）'`（**中文正确**） |
| 5 | 不带令牌 `/me` | 401 `UNAUTHENTICATED` |
| 6 | 伪造令牌 `/me` | 401 `TOKEN_INVALID` |
| 7 | 登出 | 204 |
| 8 | 重复登出 | 204（幂等） |
| 9 | 登出后用原令牌 | 401 `TOKEN_REVOKED` |
| 10 | 垃圾令牌登出 | 204（不泄露令牌是否曾有效） |
| 11 | 健康检查 | 200（白名单生效） |

### 5. 全量回归

- `mvn test` → 25/25 通过。按纪律只跑一次。

## 实施日志（短期）

- 2026-10-03：用 `BCryptPasswordEncoder` 生成演示口令 `Demo@2026` 的哈希并自校验（`VERIFY=true`）。
  **此前 `data.sql` 里放的是一个来路不明的哈希**——没人知道对应明文，演示时登录不上还得反向猜。
  现在明文固定为 `Demo@2026` 并写在 `data.sql` 注释与 README 中（本地演示数据，非真实凭据）。
- 2026-10-03：`mvn test` 首次运行 1 个失败——`logoutShouldRevokeTokenAndBeIdempotent` 期望 204 实得 401。
  定位为设计问题而非测试问题，见下方"完成结论"。

## 完成结论

- 状态：**已验收**

### 一个由测试暴露出来的真实设计问题

**现象**：登出后再次登出，`JwtAuthenticationFilter` 在控制器之前就把已拉黑的令牌拒了，返回 401 而不是 204。

**判断**：登出应当是**幂等**的。用户重试、或前端重复调用，诉求都是"让这个令牌失效"——
而它已经失效了，**目标已达成，失败响应只会让前端多写一个无意义分支**。

**修法**：把 `/api/auth/logout` 移入公开白名单。这不是为了"让测试通过"而放水：

- 登出在语义上**不需要已认证身份**——令牌本身就是凭证，有效就拉黑，无效就什么都不做
- 它**不返回任何受保护数据**
- 安全性未降低：仍会解析令牌；垃圾令牌同样返回 204，**不泄露令牌是否曾经有效**

**另一个可能的修法是改测试期望为 401**，但那样会留下一个"重试登出就报错"的接口。
**测试在这里是对的，代码是错的。**

### 新增/变更的错误码

`BAD_CREDENTIALS`、`ACCOUNT_LOCKED`、`ACCOUNT_DISABLED`、`TOKEN_EXPIRED`、`TOKEN_INVALID`、`TOKEN_REVOKED`、`UNAUTHENTICATED`、`TENANT_CONTEXT_MISSING`、`VALIDATION_FAILED`

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [auth 模块笔记](../modules/auth.md) | 失败计数落库而非只放 Redis 的理由；登出走白名单的理由 |
| [02 架构](../02-architecture.md) | 新增决策 D-14（登出端点免认证，换取幂等性） |
| [README](../../README.md) | 演示账号与口令 |
| [08 面试脚本](../08-interview-and-demo-script.md) | 新增"幂等登出"与"账号枚举"两个问答 |

### 面试可讲点

1. **登出为什么要幂等？** → 目标已达成就不该报错。而且登出不需要已认证身份，令牌本身就是凭证。
2. **错误口令和账号不存在为什么返回同一个错误码？** → 否则登录接口会变成账号探测器。防爆破靠真实用户的锁定计数，不靠差异化提示。
3. **锁定状态为什么落库而不是只放 Redis？** → **锁定必须扛得住 Redis 重启**，否则清一次缓存就等于给攻击者重置了机会。
4. **黑名单的 TTL 为什么等于令牌剩余有效期？** → 令牌自然过期后再拉黑只是白占内存。这样黑名单大小有上界 = 一个有效期窗口内的登出量。
5. **JWT 里为什么不能放邮箱、真实姓名？** → JWT 只是**签名**不是**加密**，任何人都能解开看内容。
6. **失败计数为什么先检查锁定再验密码？** → 否则锁定期内仍然在跑 BCrypt，锁定形同虚设，还白白消耗 CPU。

### 后续任务

T-005（租户上下文与持久层强制隔离）—— `CurrentUser` 机制已就位，下一步把它接到 MyBatis 上，并写"忘记带 tenantId"的失败测试锁死 I-01。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(auth): issue and validate JWT with account lockout` | 主体实现 |
| `test(auth): cover bad credentials, lockout, revoked and tampered tokens` | 测试 |
| `fix(auth): make logout idempotent by allowing it without authentication` | 由测试暴露的设计修正 |
| `refactor(test): replace full RedisConnection double with a focused mock` | 回退过度设计 |

%%
本卡已完成验收。稳定的接口与状态定义已回写 auth 模块笔记。
终端的原始输出未长期保存。
%%
