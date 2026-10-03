# 合同智能审查平台

> 上传合同 → 提取要素 → 规则校验 → AI 识别风险条款并给出原文证据 → 人工复核 → 报告页。
>
> 核心工程价值不在"接了 AI"，而在**给概率性的 AI 输出加上工程约束**：
> AI 只能产出带原文证据和置信度的**候选**结论，能否采信由确定性规则和人工决定。

---

## 1. 技术栈

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21（Spring Boot 3.3.5） |
| 持久层 | MyBatis-Plus 3.5.7 + MySQL 8 |
| 建表 | `schema.sql` + `data.sql`（**未用 Flyway**，见技术债） |
| 缓存 / 幂等 / 限流 | Redis |
| 文件存储 | 本地目录 `./data/files`（**未用 MinIO**） |
| 鉴权 | Spring Security + JWT |
| 文档解析 | Apache PDFBox 3 + POI 5 |
| AI | `RestClient` 直连 DeepSeek（**未用 Spring AI**） |
| 前端 | Vue 3 + Vite + Element Plus |
| 测试 | JUnit 5 + H2 内存库 + 应用内 Mock 桩（**未用 Testcontainers / WireMock**） |

> 每一项"未用"都是主动决策，理由记录在 [`docs/02-architecture.md`](docs/02-architecture.md#关键决策记录) 的 D-01 ~ D-13。

---

## 2. 环境要求

| 组件 | 版本 | 本机实际状态（2026-10-03 实测） |
| --- | --- | --- |
| JDK | **21** | ⚠️ 原 PATH 指向 JDK 1.8、`JAVA_HOME` 指向 JDK 19，需改为 JDK 21 |
| Maven | 3.9+ | 3.9.9 ✅ |
| MySQL | 8.0+ | Windows 服务 `MySQL80`，监听 3306 ✅ |
| Redis | 5+ | Windows 服务 `Redis`，监听 6379 ✅（本机为 3.2.100，见下方注意事项） |
| Node.js | 20+ | v24.18.0 ✅（仅前端需要） |

**本项目不需要 Docker。**

> [!note] 关于本机 Redis 3.2.100
> 该版本较老，缺少部分新命令。本项目只用 `GET/SET/EXPIRE/DEL` 与字符串自增，
> 均在 3.2 支持范围内。若用到 `Stream` 等新特性需升级。

---

## 3. 首次启动步骤

> [!tip] 一键脚本已经帮你做完 3.1~3.3
> 直接运行下面这条，脚本会自动校验 JDK、设置凭据、检查中间件、启动应用：
> ```powershell
> .\run-dev.ps1
> ```
> 下面的手工步骤仅在你需要单独排查时使用。

### 3.1 创建数据库

本机已创建（2026-10-03），如需在其他机器重建：

```sql
CREATE DATABASE IF NOT EXISTS contract_review
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;
```

建表与初始数据由 Spring Boot 在启动时自动执行（`application-dev.yml` 中 `spring.sql.init.mode=always`），**不需要手工执行 SQL 文件**。

### 3.2 数据库与 Redis 凭据

凭据**不写进配置文件**，有两种提供方式，任选其一：

**方式一（推荐）：本地凭据文件**

```powershell
Copy-Item src\main\resources\application-local.yml.example src\main\resources\application-local.yml
# 然后编辑 application-local.yml，填入你的 MySQL 与 Redis 密码
```

`application-local.yml` 已被 `.gitignore` 排除，**不会进入仓库**。

**方式二：环境变量**

```powershell
$env:DB_USER        = "root"
$env:DB_PASSWORD    = "<你的密码>"
$env:REDIS_PASSWORD = "<你的密码>"
```

> [!warning] 为什么不把密码写死在配置里
> 仓库是公开的。把密码写在 `application-dev.yml` 里虽然"跑起来方便"，
> 但会让面试官看到一个把口令提交进版本库的项目——**这是减分项，不是小事**。
> 因此 `application-dev.yml` 只保留 `${DB_PASSWORD:}` 这样的占位，真实值一律外部注入。

### 3.3 配置 AI 通道（可选）

```powershell
# 默认 false：走内置 Mock 桩，零额度消耗，开发/测试/断网演示都能跑
$env:AI_ENABLED = "false"

# 只在需要真实调用时打开
$env:AI_ENABLED        = "true"
$env:DEEPSEEK_API_KEY  = "sk-你的key"
```

> [!warning] 额度保护
> 接口额度仅 15 元，**真正的风险不是额度不够，而是调试时的循环调用烧光额度**。
> 因此代码里有三道限制：单份合同调用次数上限、日预算上限、以及**默认走 Mock 桩**。
> 开发阶段请保持 `AI_ENABLED=false`。

### 3.4 启动

```powershell
# 确认 JDK 21 生效
java -version

# 启动（dev profile 会连本机 MySQL 并自动建表）
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### 3.5 自检

```powershell
curl http://localhost:8080/api/health
```

期望返回：

```json
{
  "status": "UP",
  "application": "contract-review",
  "aiEnabled": false,
  "aiModel": "deepseek-chat",
  "promptVersion": "v1"
}
```

`aiEnabled` 会如实反映当前走的是真实调用还是 Mock 桩——**演示前先看这一项**，
避免出现"以为在真调模型，其实在读缓存"的误判。

### 3.6 跑测试

```powershell
mvn test
```

测试使用 H2 内存库与 Mock 桩，**不依赖本地 MySQL / Redis，也不消耗 AI 额度**。

---

## 4. 项目结构

```
contract-review-platform/
├── docs/                      项目知识库（先读 docs/README.md）
│   ├── 00-project-index.md    项目索引与模块导航
│   ├── 01-requirements-and-scope.md
│   ├── 02-architecture.md     模块边界、不变式、决策记录
│   ├── 04-tasks-and-acceptance.md   5 天冲刺任务看板
│   ├── 06-ai-session-prompts.md     与 AI 协作的提问模板
│   ├── 08-interview-and-demo-script.md  演示脚本与面试问答
│   ├── modules/               各模块设计笔记
│   └── tasks/                 单任务卡
├── src/main/java/com/demo/contract/
│   ├── auth/        登录、JWT、租户上下文
│   ├── parse/       上传、文本提取、保坐标归一化
│   ├── extract/     要素抽取 + 证据对齐算法
│   ├── rule/        确定性规则引擎（不触网）
│   ├── aireview/    AI 风险审查（只产出候选）
│   ├── workflow/    状态机、幂等、人工复核、降级
│   ├── config/      配置类
│   └── health/      自检端点
├── src/main/resources/
│   ├── application.yml / application-dev.yml
│   └── db/schema.sql / data.sql
└── src/test/        H2 + Mock 桩测试
```

---

## 5. 已知限制与技术债

> 主动列出来，不是遗忘。每条都有"为什么现在不还"的理由。

| 项 | 说明 | 为什么现在不还 |
| --- | --- | --- |
| **无迁移框架** | 用 `schema.sql`，改表靠手工 ALTER，且要同步维护 H2 版脚本 | 5 天期限内不值得学 Flyway；给一周第一件补这个 |
| **文件存本地磁盘** | 单机可用，多副本部署需替换实现 | 已按存储接口抽象，替换成本集中在一处 |
| **AI 无评测集** | 准确率无量化数据，因此结论只能是"候选" | 没有评测能力时，把 AI 定位成建议而非结论是更诚实的架构选择 |
| **前端未用 TypeScript** | 类型靠接口文档约束 | 5 天内类型对齐成本高于收益 |
| **未做** | 统计看板、批量审查、报告文件导出、向量检索、OCR、电子签章 | 范围决策，不是能力所限 |
| **权限仅两级** | 租户 + 角色 | 演示场景够用 |

---

## 6. 与 AI 协作的约定

本项目遵循「规格先行、测试驱动、小步提交」。开发时请遵守：

1. **规格不存在，不动工。** 新功能先写进 `docs/01` 与 `docs/04`，再开对话。
2. **一个对话只做一个任务。** 让 AI 先读 `docs/00` → `docs/02` → 对应模块笔记 → 任务卡。
3. **先写会失败的测试**（核心逻辑必须），再写最小实现。
4. **错误分支必须有明确错误码**，禁止用宽泛 `catch` 吞掉根因或用默认值兜底。
5. **复核记录只追加**，不覆盖历史。

可直接复制的提问模板见 [`docs/06-ai-session-prompts.md`](docs/06-ai-session-prompts.md)。
