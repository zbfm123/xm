---
title: 合同智能审查平台 · 系统架构
aliases:
  - Architecture
tags:
  - 项目
  - 架构
---

# 合同智能审查平台 · 系统架构

## 设计目标与约束

- 核心目标：把合同审查做成**可留痕、可复现、可降级**的流程，而不是一次性 AI 问答。
- 使用边界：本地 Docker Compose 起单体应用 + 前端；不设计公网多机房。
- 关键约束：
  - **时间约束（最硬）：国庆结束前交付，约 5 天。** 因此模块化单体 + 最小技术栈，不引入学习成本高的框架（见 D-01、D-07）。
  - 成本约束：AI 调用有预算上限，必须缓存 + 限流 + 段落上限。
  - 合规约束：合同正文属商业秘密；API Key 只走环境变量；日志脱敏。
  - 人力约束：单人开发，且此前未接触过微服务与 Flyway——**不用没学过的东西**。
- 关联需求：[01-需求与范围确认](01-requirements-and-scope.md)

## 系统概览

~~~mermaid
flowchart TB
    subgraph Client["浏览器 (Vue 3 + TS)"]
        UI[合同列表 / 详情 / 复核台 / 看板]
    end

    subgraph App["Spring Boot 3.3 单体（模块化包结构）"]
        Auth[auth 认证与权限]
        Parse[parse 合同解析]
        Extract[extract 要素抽取]
        Rule[rule 规则引擎]
        AiReview[ai-review AI 审查]
        Workflow[workflow 审查工作流]
        Report[report 报告与看板]
    end

    subgraph Infra["基础设施"]
        MySQL[(MySQL 8)]
        Redis[(Redis)]
        Files[/本地文件目录 data\/files/]
    end

    LLM[DeepSeek API 外部服务]

    UI --> Auth
    UI --> Parse
    UI --> Extract
    UI --> Rule
    UI --> AiReview
    UI --> Workflow
    UI --> Report

    Auth --> MySQL
    Parse --> Files
    Parse --> MySQL
    Extract --> MySQL
    Extract --> LLM
    Rule --> MySQL
    AiReview --> LLM
    AiReview --> Redis
    AiReview --> MySQL
    Workflow --> MySQL
    Workflow --> Redis
    Report --> MySQL
~~~

> [!warning] 真实性约束
> `report` 模块尚未建立独立笔记，图中保留是因为 F-08/F-09 已在本期范围内。
> 不要为了图完整而提前实现它；按 [04](04-tasks-and-acceptance.md) 的任务顺序推进。

## 模块边界

| 模块 | 负责什么 | 不负责什么 | 对外输入/输出 | 依赖 | 模块笔记 |
| --- | --- | --- | --- | --- | --- |
| auth | 登录、JWT 签发校验、租户与角色判定 | 不负责业务鉴权规则（如"谁能终审"） | 凭据 → Token / 当前用户上下文 | MySQL | [auth](modules/auth.md) |
| parse | 接收上传、格式与大小校验、文本提取与归一化 | 不判断合同内容含义、不调大模型 | 文件 → 归一化文本 + 页面结构 | MinIO、MySQL | [parse](modules/parse.md) |
| extract | 把文本转成结构化要素，维护字段级置信度与原文位置 | 不做合规判定，不做风险评价 | 文本 → `ContractElement[]` | parse 产物、LLM | [extract](modules/extract.md) |
| rule | 确定性规则校验，产出可复现的 `RuleFinding` | **不调用大模型**，不做概率判断 | 要素 + 文本 → `RuleFinding[]` | extract 产物 | [rule](modules/rule.md) |
| ai-review | 条款切分、调用大模型、schema 校验、证据对齐、置信度 | 不决定结论是否生效（交 workflow） | 文本 → `AiFinding[]`（候选） | LLM、Redis、parse 产物 | [ai-review](modules/ai-review.md) |
| workflow | 审查任务状态机、幂等、分派、人工复核、降级 | 不实现具体审查逻辑 | 事件 → 状态迁移 + 复核记录 | 全部模块 | [workflow](modules/workflow.md) |
| report | 汇总结论、生成导出文件、统计看板 | 不产生新结论，只呈现已有结论 | 结论集合 → 报告/图表 | MySQL | 待建 |

### 依赖方向（必须单向）

~~~mermaid
flowchart LR
    workflow --> ai-review
    workflow --> rule
    ai-review --> extract
    rule --> extract
    extract --> parse
    ai-review --> parse
    report --> workflow
    auth -.-> workflow
~~~

> [!important] 边界纪律
> - `rule` 不得依赖 `ai-review`，`ai-review` 也不得调用 `rule`。两者只在 `workflow` 汇合。
> - 任何模块不得直接读别的模块的表；跨模块只能通过对方暴露的接口/领域事件。
> - 违反这两条时，先更新本文档再动代码。

## 主数据流

### 流程：一份合同从上传到报告

1. **parse**：`POST /contracts` 收到文件 → 校验 MIME 与大小 → 存本地文件目录 → 提取文本 → 归一化（去页眉页脚、统一空白与全半角）→ 落 `contract_text`，计算 `textHash`。
2. **extract**：按 `textHash` 查缓存，未命中则按章节切块送大模型 → 拿到结构化字段 → **逐字段在原文中定位** `charStart/charEnd` → 定位不到或置信度低 → 标记 `LOW_CONFIDENCE` / `UNKNOWN`。
3. **rule**：读要素，纯函数式执行规则集 → 每条命中产出 `ruleCode + severity + evidence` → 落 `rule_finding`。此步骤**不触网**。
4. **ai-review**：按条款切分文本 → 组装提示词（含固定输出 schema）→ 调用大模型 → 校验 JSON schema → **证据对齐**（quote 必须能在原文指定区间命中）→ 产出候选 `ai_finding`；对齐失败 → `EVIDENCE_MISMATCH`，转人工。
5. **workflow**：把上述结果汇总为一个审查任务的状态；`PENDING` 结论等待人工复核；低置信度强制升级到主管角色。
6. **report**：只读取已生效结论（规则结论 + 已采信的 AI 结论）生成报告，报告里三类信息分栏，不混淆来源。

### 关键不变式

| 编号 | 不变式 | 违反时的表现 | 由谁保证 |
| --- | --- | --- | --- |
| I-01 | 每个查询都带 `tenantId` | 跨租户数据泄露 | auth 提供的上下文 + Repository 层强制条件 |
| I-02 | 无原文定位的 AI 结论不得进入正式结论 | 报告里出现无法核实的判断 | ai-review 证据对齐 |
| I-03 | 规则结论确定性可复现 | 同输入不同输出 | rule 纯函数 + 单元测试 |
| I-04 | AI 不可用时规则链仍可用 | 整个系统挂掉 | workflow 降级分支 |
| I-05 | 一次审查任务对同一 `textHash` 幂等 | 重复扣费、重复结论 | workflow 幂等键 + Redis |
| I-06 | 复核记录只追加不覆盖 | 审计痕迹丢失 | `review_action` 表只 insert |

## 技术选型

| 层级 | 选择 | 选择原因 | 已知限制 |
| --- | --- | --- | --- |
| 语言/运行时 | Java 21 (LTS) | 秋招主流，虚拟线程可用于并发调模型 | 部分老中间件兼容性需确认 |
| 框架 | Spring Boot 3.3.x | 生态完整，面试认可度高 | 无 |
| 持久层 | MyBatis-Plus | 查询条件多，SQL 可控，上手快 | 无自动 DDL，需自己维护建表脚本 |
| 建表与初始数据 | **`schema.sql` + `data.sql`**（`spring.sql.init`） | Spring Boot 内置，零额外依赖；`CREATE TABLE IF NOT EXISTS` 可重复执行 | 无版本化迁移能力，改表靠手工 ALTER（**够 5 天用，不够长期用**） |
| 缓存/幂等/限流 | Redis | 缓存 AI 结果、幂等键、令牌桶限流 | 需处理 Redis 不可用时的降级 |
| 文件存储 | **本地磁盘目录**（`./data/files`） | 少一个组件少一类故障；接口仍按"存储服务"抽象，日后可换 OSS | 单机，不适用于多副本 |
| 鉴权 | Spring Security + JWT | 标准方案，可讲过滤器链 | Token 失效需 Redis 黑名单 |
| 文档解析 | Apache PDFBox + POI | 纯 Java，无外部进程 | 扫描件不支持（本期明确不做） |
| AI 接入 | **`RestClient` 直连 DeepSeek**（OpenAI 兼容接口） | 只有 DeepSeek Key；不引入 Spring AI 的版本不确定性，代码量约 100 行 | 供应商耦合在 `DeepSeekClient` 一个类里，换模型改这一处 |
| AI 测试替身 | **应用内 `AiClient` Mock 开关**（`app.ai.enabled=false` + 桩 JSON） | 无需额外框架；同一开关同时服务于测试和演示降级 | 桩数据要自己写；不如 WireMock 灵活 |
| 集成测试 | `@SpringBootTest` + **H2 内存库** | 不依赖 Docker，跑得快 | H2 与 MySQL 方言有差异，涉及 MySQL 特有用法的 SQL 要单独验证 |
| 前端 | Vue 3 + Vite + Element Plus（**可不用 TypeScript**） | 上手快，演示好看；赶时间可省类型标注 | 不做类型对齐，靠接口文档约束 |
| 部署 | **直接使用本机 Windows 服务**（MySQL80 + Redis） | 本机已装好且运行中；**没有安装 Docker**，装它反而要额外折腾 WSL | 换机器需手工准备两个服务；`docker-compose.yml` 列为可选交付物 |

> [!important] 环境事实决定的一次方案变更（2026-10-03 实测）
> 原本计划用 `docker compose` 起 MySQL 与 Redis。实测发现：
> 本机 **MySQL 8.0.40 与 Redis 均已是运行中的 Windows 服务**，而 **Docker 未安装**。
>
> 处理方式：**把 Docker 从方案里去掉，而不是去装 Docker。**
> 理由与整个项目的取舍原则一致——**能用已有能力解决的，就不引入新工具**。
> 省下的时间（装 Docker + WSL + 拉镜像，通常 1~2 小时且容易踩坑）直接投到 T-013 证据对齐上。

> [!important] 技术选型的第一原则（本次冲刺）
> **不用没学过的东西。**
> 微服务、Flyway、Testcontainers、WireMock、Spring AI 全部排除——不是它们不好，而是在 5 天期限下，
> 每个新框架都要吃掉半天到一天的学习与踩坑成本，而这些成本换不来面试上的加分。
> 面试官更愿意听"我为什么**没**用微服务"，而不是"我照着教程搭了个微服务"。

## 接口与数据所有权

| 数据/接口 | 所有者 | 写入者 | 读取者 | 校验边界 | 敏感级别 |
| --- | --- | --- | --- | --- | --- |
| `user` / `tenant` | auth | auth | auth | 注册/登录入口 | 敏感（含哈希） |
| `contract` | parse | parse | 全部模块 | 上传入口校验 MIME/大小/租户额度 | 敏感 |
| `contract_text` | parse | parse | extract、ai-review、report | 解析出口必须归一化完成 | 敏感（商业秘密） |
| `contract_element` | extract | extract | rule、report | 写入前必须有 `charStart/charEnd` 或显式 `UNKNOWN` | 敏感 |
| `rule_finding` | rule | rule | workflow、report | 必须有 `ruleCode` 与 evidence | 内部 |
| `ai_finding` | ai-review | ai-review | workflow、report | **必须过 schema 校验 + 证据对齐**才写入 | 内部 |
| `review_action` | workflow | workflow | report | 只追加，必须带操作人与理由 | 内部（审计） |
| LLM API Key | 运维（环境变量） | 无 | ai-review、extract | 不得出现在代码/日志/知识库 | **最高** |

## 关键决策记录

| 编号 | 日期 | 决策 | 可选方案 | 选择理由 | 后续影响 |
| --- | --- | --- | --- | --- | --- |
| D-01 | 待填 | 模块化单体，不拆微服务 | Spring Cloud 微服务 | **5 天期限 + 没有微服务基础**。微服务在单机演示中只增加运维成本、减少可讲深度；模块边界用包结构与接口约束保证 | 全部模块；面试可讲"边界先于拆分" |
| D-02 | 待填 | AI 输出一律"候选"，不直接生效 | AI 结论直接入库为结论 | 概率性输出的错误代价由业务承担，必须留人工否决权 | ai-review、workflow、report |
| D-03 | 待填 | 证据对齐作为独立校验层 | 信任模型返回的引用 | 模型会幻觉出不存在于原文的条款，必须机器校验 | ai-review 核心算法 + 测试 |
| D-04 | 待填 | 规则引擎与大模型完全解耦 | 用 AI 判断一切 | 确定性判断不该付概率代价，且规则可复现可测试 | rule、ai-review 互不依赖 |
| D-05 | 待填 | AI 结果按 `textHash` 缓存 | 每次都调 | 成本与稳定性 | ai-review、Redis |
| D-06 | 待填 | schema 异常显式失败，不用默认值兜底 | 用 try/catch 吞掉填默认值 | 吞掉根因会产出看似正常的脏数据（对应工作流 Lean Mode） | ai-review、workflow |
| D-07 | 待填 | AI 接入用 `RestClient` 直连 DeepSeek，不用 Spring AI | Spring AI / LangChain4j | 只有 DeepSeek 一个供应商；自写客户端约 100 行，无版本不确定性；供应商耦合集中在一处 | `DeepSeekClient`；换模型只改这一个类 |
| D-08 | 待填 | 建表用 `schema.sql` + `data.sql`，不用 Flyway | Flyway / Liquibase | **没学过 Flyway**，5 天内不值得学；演示项目没有多环境迁移需求 | 改表靠手工 ALTER；**这是本次已知技术债，面试时要主动说** |
| D-09 | 待填 | 文件存本地磁盘，不用 MinIO | MinIO / 云 OSS | 少一个容器、少一类连接故障；已按存储接口抽象，可替换 | 单机限制；多副本部署需替换实现 |
| D-10 | 待填 | 外部依赖测试用应用内 Mock 开关，不用 WireMock | WireMock / Testcontainers | 一个开关同时服务测试与演示降级，省一个框架的学习成本 | 桩数据手工维护 |
| D-11 | 待填 | 前端可不用 TypeScript | TS / 纯 JS | 5 天内 TS 的类型对齐成本高于收益 | 类型靠接口文档约束，**面试时如实说明** |
| D-12 | 待填 | AI 调用设单合同次数上限 + 成本计数器 + 默认走 Mock | 不设限 | **接口额度仅 15 元，真正的风险是调试时的循环调用烧光额度**；开发测试默认 `AI_ENABLED=false` 可做到零消耗 | ai-review、extract；面试可讲"成本也是一种正确性约束" |
| D-13 | 待填 | 删除合同必须定向清 `textHash` 缓存 | 只删数据库行 | 否则重新上传同一份合同会命中旧缓存，返回**与本次无关的过期结论** | parse、ai-review；对应 [DE-01](#删除与缓存失效de-01) |
| D-14 | 2026-10-03 | **登出端点免认证**，以换取幂等性 | 要求已认证 | 若要求已认证，用已失效令牌再登出会被过滤器拦成 401；但此时用户诉求（令牌失效）**已经达成**。登出不返回受保护数据，令牌本身就是凭证，安全性未降低 | auth；由 T-004 的失败测试暴露，是**改代码而非改测试** |
| D-15 | 2026-10-03 | 登录失败计数与锁定**落库**，不只放 Redis | 只放 Redis | 清一次缓存就等于给攻击者重置了爆破机会。**锁定状态必须能扛住 Redis 重启** | auth；代价是多一次数据库写入 |
| D-16 | 2026-10-03 | 登出黑名单 TTL = 令牌剩余有效期 | 永久或固定 TTL | 令牌自然过期后再拉黑只是白占内存；这样黑名单大小有上界（= 一个有效期窗口内的登出量） | auth、Redis |

### 设计细节记录

#### 删除与缓存失效（DE-01）

**问题**：AI 结果按 `textHash` 缓存（I-05 幂等需要）。用户删除合同后重新上传同一份文件，若缓存未清，
会直接命中旧结论——**看起来正常，实际是过期数据**。

**方案**：删除走"软删除 + 异步硬清理"两段：

1. 立即：`contract.deleted = 1`，列表不再显示（用户感知即时）
2. 异步：清理磁盘文件、6 张关联表数据、**Redis 中该 `textHash` 的全部 AI 缓存键**
3. 清理失败进入重试队列，且**记录未完成清理的 `textHash`**，下次上传该文件时强制不走缓存

> [!warning] 为什么这个细节值得单独记
> 它是"缓存与数据一致性"的典型陷阱，且是**本项目 CRUD 里唯一需要动脑的地方**。
> 面试时这类"我踩过的具体坑"比泛泛谈"缓存穿透/击穿/雪崩"有说服力得多。

## 变更规则

- 新功能先回到 [01-需求与范围确认](01-requirements-and-scope.md)，确认是否在范围内。
- 涉及模块边界、数据流或技术选型时，**先更新本文档，再创建实现任务**。
- 模块内部行为写入对应模块笔记；一次性测试输出留在任务卡，不写入本文档。
- 任何削弱 I-01~I-06 不变式的改动，必须在 [07](07-change-and-delivery.md) 记录并说明补偿措施。
