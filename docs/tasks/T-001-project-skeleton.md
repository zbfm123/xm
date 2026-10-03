---
title: T-001 项目骨架与依赖收敛
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 进行中
---

# T-001 项目骨架与依赖收敛

## 任务边界

- 所属模块：—（基础设施）
- 对应需求/验收：A-10 前半
- 目标行为：项目能编译、测试能跑、应用能起来并响应 `/api/health`
- 本任务不做：不写任何业务代码（登录、上传、规则、AI 一律不做）
- 前置依赖：T-000（AI Key 与演示环境确认）✅ 已完成
- 涉及的不变式：无（本任务只搭骨架）

> [!note] 本任务的特殊之处
> 这是唯一一个**不适用"先写失败测试"**的任务——骨架本身就是让测试能跑起来的前提。
> 因此它是 [04](../04-tasks-and-acceptance.md#测试策略冲刺版已简化) 里允许"先实现后验证"的例外，需在此记录原因。

## 环境事实（2026-10-03 实测）

| 组件 | 状态 | 备注 |
| --- | --- | --- |
| JDK | **装 JDK 21 中** | 原 PATH 指向 JDK 1.8、JAVA_HOME 指向 JDK 19（已停止维护） |
| Maven | 3.9.9 | `D:\xiazai\apache-maven-3.9.9-bin` |
| MySQL | 8.0.40 运行中 | Windows 服务 `MySQL80`，监听 3306 |
| Redis | 运行中 | 服务 `Redis`，`D:\redis\Redis-x64-3.2.100`，监听 6379 |
| Docker | **未安装，且不需要** | 改用本机 MySQL + Redis，省去装 Docker 与 WSL 的时间 |
| Node | v24.18.0 | 前端备用 |

### 因环境事实做出的调整

| 原方案 | 调整后 | 原因 |
| --- | --- | --- |
| `docker compose` 起 MySQL + Redis | 直接连本机两个 Windows 服务 | Docker 未装，且本机服务已可用 |
| JDK 21 | 改为 **`<java.version>21</java.version>`**，等 JDK 21 装好即用 | 用户正在安装 |

## 验收示例

### 正常路径

- 给定：JDK 21 已安装，MySQL 服务运行中
- 当：执行 `mvn -q test`
- 则：测试通过；`ContractReviewApplicationTests` 两条用例全绿

### 异常路径

- 给定：`app.ai.enabled` 未配置
- 当：启动应用
- 则：启动失败并明确指出缺失的配置项（**不允许静默使用默认值**）

### 明确不该发生的事

- 不得在 `pom.xml` 里引入 Flyway / MinIO SDK / Spring AI / Testcontainers / WireMock / Lombok（决策 D-07~D-11）
- 不得在配置文件里写死真实 API Key 或数据库密码
- 不得在测试中真实调用 DeepSeek（必须走 `ai.enabled=false` 的 Mock 桩）

## 交付物清单

| 文件 | 作用 |
| --- | --- |
| `pom.xml` | 依赖收敛，含"为什么不装某依赖"的注释 |
| `src/main/resources/application.yml` | 主配置，含 AI 通道与成本上限配置 |
| `src/main/resources/application-dev.yml` | 本机 MySQL + `sql.init` 建表 |
| `src/main/resources/db/schema.sql` | MySQL 建表（`IF NOT EXISTS` 可重复执行） |
| `src/main/resources/db/data.sql` | 两个租户 + 三个演示账号（**全部虚构**） |
| `src/test/resources/application-test.yml` | H2 内存库，禁用 Redis 自动配置 |
| `src/test/resources/db/schema-h2.sql` | H2 版建表（去掉 MySQL 专有子句） |
| `src/main/java/.../ContractReviewApplication.java` | 启动类，注释里写明模块边界纪律 |
| `src/main/java/.../config/SecurityConfig.java` | BCrypt 编码器（Day 1 占位版） |
| `src/main/java/.../health/HealthController.java` | 自检端点，暴露 AI 通道开关状态 |
| `src/test/java/.../ContractReviewApplicationTests.java` | 上下文加载 + 健康端点集成测试 |
| `.gitignore` | 排除敏感配置与运行时数据 |

## TDD 记录

### 1. 失败测试（RED）

- 说明：**本任务不适用**。骨架是让测试得以运行的前提，属 [04](../04-tasks-and-acceptance.md) 中登记的例外。
- 替代验证方式：先让 `mvn test` 在**没有源文件**的状态下失败一次，确认 Maven 工具链本身可用（见下）。

### 2. 最小实现（GREEN）

- 拟修改文件：见上方交付物清单
- 最小改动说明：只做"能编译 + 能起来 + 能自检"，不提前实现任何业务能力
- 不新增的复杂度：
  - 不做全局异常处理器与统一响应包装 —— 等 Day 2 有真实端点时再定结构
  - 不搭完整 JWT 过滤器链 —— 属于 T-004，现在搭只会是空壳
  - 不引入 Lombok —— 多一个 IDE 插件依赖，getter/setter 手写更显式，面试时可读性更好

### 3. 验证结果

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| `mvn test` | ✅ 通过 | `Tests run: 2, Failures: 0, Errors: 0` / BUILD SUCCESS / 1 分 48 秒 |
| 应用启动（dev profile） | ✅ 通过 | 第 9 秒就绪，`Started ContractReviewApplicationTests`（测试）/ dev 启动无异常 |
| `/api/health` 返回 UP | ✅ 通过 | `{"status":"UP","application":"contract-review","aiEnabled":false,"aiModel":"deepseek-chat","promptVersion":"v1"}` |
| MySQL 真实建表 | ✅ 通过 | `contract_review` 库中出现 `sys_user`、`tenant` 两表 |
| 初始数据写入 | ✅ 通过 | 2 个租户 + 3 个账号；角色分别为 `LEGAL_STAFF` / `LEGAL_LEAD` / `LEGAL_STAFF` |
| **中文编码正确** | ✅ 通过 | 库中「演示租户（虚构）」前两字节为 `E6BC94 E7A4BA`，即「演示」的 UTF-8 编码；字符长度 8 与预期一致 |
| 测试未真实调用 AI | ✅ 通过 | test profile 中 `ai.enabled=false`，且断言 `aiEnabled=false` |
| Redis 连通 | ✅ 通过 | `redis-cli` 认证后 `ping` → `PONG` |

> [!tip] 为什么专门验证"中文编码"
> 控制台显示的乱码和数据库真正存错是两件完全不同的事。
> 只看控制台会把"控制台按 GBK 解码"误判成"数据存坏了"，从而浪费时间去改配置。
> **验证要看字节，不要看终端的脸。**

## 附带修好的环境问题

| 问题 | 处理 |
| --- | --- |
| `java` 指向 JDK 1.8、`JAVA_HOME` 指向 JDK 19 | 下载安装 Temurin JDK 21.0.12.1 到 `D:\java\jdk-21`；设置用户级 `JAVA_HOME` |
| 系统 PATH 里残留 jdk-1.8 / jdk-19（清理需管理员权限） | 不改系统 PATH，改为在项目内锁定：`.mvn/jvm.config` 指定 `-Djava.home`（**该文件必须纯 ASCII，中文注释会导致 Maven 启动失败**） |
| PowerShell 控制台中文乱码 | 写入 `HKCU\Console` 的 `CodePage=65001` + `OutputEncoding=65001` |

## 实施日志（短期）

- 2026-10-03：环境探测。发现 PATH 上 `java` 指向 JDK 1.8、`JAVA_HOME` 指向 JDK 19；Docker 未安装但 MySQL 8.0.40 与 Redis 均作为 Windows 服务运行。据此把"docker compose 起中间件"从方案中移除。
- 2026-10-03：生成骨架文件。因 JDK 21 正在安装，`mvn test` 尚未运行。
- 2026-10-03：**JDK 21 安装失败一次**——Adoptium 官方下载触发 GitHub 重定向，连接被重置（`curl: (56) Recv failure`）。改为重试同一地址后成功（195.6 MB）。教训：这类下载要做重试或换镜像，不要一次失败就换方案。
- 2026-10-03：`.mvn/jvm.config` 首次写入时带了中文注释，Maven 报 `ClassNotFoundException: #`——**该文件由 JVM 直接读取，中文注释会被按 GBK 解析而损坏配置**。改为纯 ASCII 单行后正常。
- 2026-10-03：`mvn test` 通过（2/2）。dev profile 启动成功，真实 MySQL 建表与初始数据均正确，中文以 UTF-8 正确存储。
- 2026-10-03：排查"中文乱码"时确认是控制台解码问题而非数据问题（用字节验证），并顺手把用户级控制台编码改为 UTF-8。

## 完成结论

- 状态：**已验收**
- 交付结果：13 个骨架文件 + `.mvn/jvm.config`；`mvn test` 全绿；dev profile 可连真实 MySQL 并自动建表
- 新增/变更的错误码：无
- 需要回写的长期事实：环境事实与"本机服务代替 Docker"决策 → 已写入本卡与 [02](../02-architecture.md)；`mvn test` 命令与凭据 → 已写入 [README](../../README.md)
- 面试可讲点：
  1. **"本机没有 Docker，我把中间件依赖从方案里去掉，而不是去装 Docker"** —— 范围决策，已补进 [08](../08-interview-and-demo-script.md)
  2. **"H2 测试通过不等于 MySQL 通过，所以我在真实 MySQL 上又验了一遍"** —— 测试替身的边界意识
  3. **"验证中文编码要看字节而不是看终端"** —— 区分"显示错"和"存错"
- 后续任务：T-002（验证中间件连通，已随本任务一并验证）、T-003（建表脚本已在真实 MySQL 验证）

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `chore(project): bootstrap spring boot skeleton without extra frameworks` | 骨架与依赖收敛 |
| `chore(config): add dev/test profiles and sql init scripts` | 配置与建表脚本 |
| `test(smoke): assert context loads and health endpoint reports ai state` | 骨架自检测试 |

%% 
任务完成后：把"环境事实"与"本地服务代替 Docker"的决策回写到 02-architecture.md 与 07。
本卡的临时探测输出不长期保存。
%%
