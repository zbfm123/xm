---
title: 认证与权限 auth
aliases:
  - auth
  - 权限
tags:
  - 项目
  - 模块
  - 安全
status: 未开始
---

# 认证与权限 auth

## 0. 一句话定位

只负责「你是谁、你属于哪个租户」两件事，并把租户上下文安全地传给后续所有请求。

**不变量**：I-01（每个查询都带 `tenantId`）。

## 1. 职责与边界

### 模块职责

登录鉴权、签发与校验 JWT、解析当前用户与租户上下文、角色判定（法务专员 / 法务主管 / 演示只读）。
**数据隔离的执行方式**：不在 Service 层逐个手写 `where tenantId = ?`，而是在持久层统一注入该条件。

### 不负责什么

- 不负责业务鉴权规则（例如"谁能终审低置信度结论"）→ 交给 [workflow](workflow.md)
- 不负责用户管理后台（本期无此需求）
- 不负责审计记录 → 属于 [workflow](workflow.md#4-数据与接口) 的 `review_action`

### 上下游关系

| 方向 | 模块/系统 | 交换内容 | 约定 |
| --- | --- | --- | --- |
| 输入 | 浏览器 | 用户名 + 密码 / Bearer Token | JSON / Header，禁止在 URL 传 Token |
| 输出 | 全部业务模块 | `CurrentUser{userId, tenantId, role}` | 通过 `ThreadLocal`/请求作用域上下文提供，**不通过方法参数层层透传** |

### 允许的依赖

- 允许调用：MySQL（`tenant` / `user` 表）
- 禁止调用：任何业务模块。auth 是叶子依赖，被依赖但不依赖别人。

## 2. 功能清单

| 编号 | 功能 | 输入 | 输出 | 关联需求 |
| --- | --- | --- | --- | --- |
| M-A01 | 登录签发 Token | 用户名 + 密码 | JWT + 角色 | F-01 |
| M-A02 | Token 校验与上下文注入 | Bearer Token | `CurrentUser` | F-01 |
| M-A03 | 租户条件强制注入 | 任意查询 | 自动追加 `tenantId` | F-01 / A-01 |
| M-A04 | 登出（Token 失效） | Token | 加入 Redis 黑名单 | F-01 |

## 3. 核心流程

### 流程：登录

1. 收到用户名+密码，按用户名（非租户）定位用户记录。
2. BCrypt 校验密码；**失败时统一返回"用户名或密码错误"**，不区分账号是否存在。
3. 连续失败计数（Redis），超阈值锁定并返回 `ACCOUNT_LOCKED`。
4. 签发 JWT，载荷含 `userId`、`tenantId`、`role`、`exp`、`jti`。

### 流程：请求上下文注入

1. 过滤器解析并校验签名与过期时间。
2. 查 Redis 黑名单（`jti` 是否已登出）。
3. 构造 `CurrentUser` 放入请求作用域；**请求结束必须清理**，避免线程复用导致上下文串号。
4. 持久层拦截器读取 `CurrentUser.tenantId`，自动拼接查询条件。

### 异常与降级路径

| 情况 | 判定方式 | 系统行为 | 是否转人工 |
| --- | --- | --- | --- |
| 上下文缺失 | 拦截器取不到 `CurrentUser` | **直接抛异常拒绝对话，绝不退化为"查全部"** | 否 |
| Token 过期 | `exp` 超期 | 401 + `TOKEN_EXPIRED` | 否 |
| Token 已登出 | Redis 黑名单命中 | 401 + `TOKEN_REVOKED` | 否 |
| Redis 不可用 | 连接异常 | 黑名单校验失败时**拒绝请求**（安全优先），返回 503 | 否 |

## 4. 数据与接口

### 数据结构

| 字段/对象 | 类型 | 含义 | 必填 | 来源 | 去向 |
| --- | --- | --- | --- | --- | --- |
| `Tenant.id` | Long | 租户标识 | 是 | 迁移脚本 | 全部业务表 |
| `User.id` | Long | 用户标识 | 是 | 注册/初始化 | 审计字段 |
| `User.passwordHash` | String | BCrypt 哈希 | 是 | 登录时校验 | 从不外传 |
| `CurrentUser.role` | Enum | `LEGAL_STAFF` / `LEGAL_LEAD` / `DEMO_READONLY` | 是 | JWT | workflow 鉴权 |

### 接口/事件

| 名称 | 调用方 | 输入 | 成功输出 | 失败输出 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `POST /api/auth/login` | 前端 | username, password | `{token, role, tenantId}` | 401 `BAD_CREDENTIALS` / 423 `ACCOUNT_LOCKED` | 限流保护 |
| `POST /api/auth/logout` | 前端 | Bearer Token | 204 | 401 | 写 Redis 黑名单，TTL = 剩余有效期 |
| `GET /api/auth/me` | 前端 | Bearer Token | 当前用户信息 | 401 | 用于刷新页面后恢复状态 |

## 5. 状态、错误码与排查

| 错误码 | 触发条件 | 用户可见结果 | 系统行为 | 优先排查位置 | 是否可重试 |
| --- | --- | --- | --- | --- | --- |
| `BAD_CREDENTIALS` | 密码不符 | "用户名或密码错误" | 计数 +1 | `AuthService#login` | 是 |
| `ACCOUNT_LOCKED` | 连续失败超阈值 | "账号已锁定，请稍后再试" | Redis 计数生效 | 登录限流配置 | 是（等待后） |
| `TOKEN_EXPIRED` | `exp` 超期 | 跳转登录 | 401 | JWT 配置 | 重新登录 |
| `TOKEN_REVOKED` | 黑名单命中 | 跳转登录 | 401 | 黑名单校验 | 重新登录 |
| `TENANT_CONTEXT_MISSING` | 上下文缺失 | "会话已失效" | **拒绝查询** | 持久层拦截器 | 重新登录 |

> [!warning] 这里最容易犯的错
> 上下文缺失时"顺手查全部数据"，会直接击穿 I-01。
> 正确行为是**失败**，不是降级。这是少见的"降级反而是错误"的场景。

## 6. 测试与验收

### 单元测试（严格 TDD）

| 场景 | 类型 | 前置条件 | 操作 | 预期结果 |
| --- | --- | --- | --- | --- |
| 密码正确签发 Token | 单元 | 存在用户 | 调用登录 | 返回有效 JWT，含正确 tenantId |
| 密码错误 | 单元 | 存在用户 | 错误密码登录 | `BAD_CREDENTIALS`，不泄露账号是否存在 |
| 连续失败锁定 | 单元 | 阈值 = 5 | 连续失败 5 次 | 第 5 次返回 `ACCOUNT_LOCKED` |
| Token 过期 | 单元 | 构造过期 Token | 校验 | `TOKEN_EXPIRED` |
| 上下文缺失拒绝 | 单元 | 无上下文 | 调用带租户条件的查询 | **抛异常，不返回全量** |

### 集成测试

| 场景 | 依赖替身 | 覆盖的验收项 |
| --- | --- | --- |
| 两租户交叉查询 | `@SpringBootTest` + H2 | A-01 |
| 登出后 Token 失效 | 内嵌 Redis 替身或 Mock 黑名单 | A-01 相关 |
| "忘记带 tenantId"的查询 | `@SpringBootTest` + H2 | I-01 锁死 |

### 手工验收

| 步骤 | 预期结果 |
| --- | --- |
| 用租户 A 账号登录，访问合同列表 | 只见 A 租户数据 |

## 7. 实现定位

- 主要代码位置：`src/main/java/com/demo/contract/auth`
- 测试位置：`src/test/java/com/demo/contract/auth`
- 数据库迁移：`src/main/resources/db/migration/V1__tenant_user.sql`
- 相关配置：`application.yml` 中 `app.jwt.*`、`app.security.*`
- 关联任务：[T-004 / T-005](../04-tasks-and-acceptance.md#待开始)

## 8. 长期决策与待办

### 稳定决策

| 日期 | 决策 | 原因 | 影响 |
| --- | --- | --- | --- |
| 待填 | 租户隔离在持久层统一注入，不下放给每个 Service | 靠人记得写条件必然漏；用拦截器 + 失败测试锁住 | 全部业务模块 |
| 待填 | 上下文缺失时失败而非降级 | 唯一比"不可用"更糟的是"返回了不该看的数据" | I-01 |

### 面试可讲点

- **租户隔离你怎么保证不遗漏？** → 不靠代码评审，靠持久层统一注入 + 一条"忘记带 tenantId"的失败测试。
- **为什么上下文缺失时不返回全部数据？** → 这是少数"降级是错误"的场景；可用性不能凌驾于数据边界。
- **JWT 登出怎么做？** → JWT 本身无状态，用 Redis 黑名单存 `jti`，TTL 设为 Token 剩余有效期，避免黑名单无限膨胀。

### 待办

- [ ] 演示只读账号的写操作拦截规则（属于 workflow，需在此登记）
