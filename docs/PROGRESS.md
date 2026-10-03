---
title: 进度记录（跨会话续接用）
tags:
  - 项目
  - 进度
---

# 进度记录

> [!important] 这份文件的作用
> 换新会话、或者隔一天回来时，**先读这一页**，就知道做到哪了、下一步做什么。
> 只记"事实与结论"，不记终端输出。

## 当前状态

- **阶段**：Day 3 / M3「有核心」—— 进行中
- **当前任务**：T-013 证据对齐算法（项目核心）
- **日期**：2026-10-03
- **测试**：`mvn test` → **153/153 通过**
- **可访问**：<http://localhost:8080>（自带调试台，含规则校验面板）

## 已完成

| 任务 | 交付物 | 状态 |
| --- | --- | --- |
| 知识库骨架 | `docs/` 23 个 md 文件，0 坏链 | ✅ |
| 需求与范围 | [01](01-requirements-and-scope.md) 含 CRUD 定位说明 | ✅ |
| 架构与决策 | [02](02-architecture.md) 含 D-01~D-23、不变式 I-01~I-06 | ✅ |
| 任务拆分 | [04](04-tasks-and-acceptance.md) 22 个 P0 任务 / 5 天里程碑 | ✅ |
| 面试脚本 | [08](08-interview-and-demo-script.md) 简历 + 演示 + 问答 | ✅ |
| T-001 骨架 | `mvn test` 通过（Java 21.0.12.1 + Spring Boot 3.3.5） | ✅ |
| T-002 中间件 | MySQL 8.0.40 + Redis 3.2.100 连通 | ✅ |
| T-003 建表与数据 | 真实 MySQL 中 4 张表 + 2 租户 + 3 账号 | ✅ |
| T-004 登录与 JWT | 真实 MySQL 上 11 项端到端验证全过 | ✅ |
| T-005 租户隔离 | **架构测试锁死 I-01（已验证会失败）** | ✅ |
| T-006 上传幂等 | 内容哈希幂等 + 魔数校验 + 本地存储 | ✅ |
| T-007 列表与详情 | 分页/搜索/状态筛选，非法参数返回 400 | ✅ |
| T-008 删除级联 | **软删除 + 定向清 AI 缓存** | ✅ |
| **T-009 文本提取** | **真实 PDF/DOCX（含表格）+ 保坐标归一化** | ✅ |
| **T-010 解析失败显式化** | **加密/扫描件/损坏三类失败均不留半份正文** | ✅ |
| **T-011 规则引擎** | **结果三分 HIT/PASS/UNDETERMINED，出错隔离，编码唯一校验** | ✅ |
| **T-012 规则实现** | **金额/日期/条款/主体四条规则 + 中文大写金额解析** | ✅ |
| **确定性要素抽取** | 纯正则抽金额/日期/主体，零 AI 成本 | ✅ |
| **自带调试台** | **<http://localhost:8080> 浏览器可走完整流程（含规则校验面板）** | ✅ |
| GitHub 仓库 | <https://github.com/zbfm123/xm> 提交归属账号正确 | ✅ |

任务卡：[T-001](tasks/T-001-project-skeleton.md) ｜ [T-004](tasks/T-004-auth-login-jwt.md) ｜ [T-005~008](tasks/T-005-008-tenant-and-contract-crud.md) ｜ [T-009~010](tasks/T-009-010-text-extraction-and-normalization.md)

## 环境已就绪（本机实测）

| 组件 | 值 |
| --- | --- |
| JDK | **Temurin 21.0.12.1**，装在 `D:\java\jdk-21` |
| `JAVA_HOME` | 用户级已指向 `D:\java\jdk-21` |
| 项目内 JDK 锁定 | `.mvn/jvm.config` → `-Djava.home=D:\java\jdk-21`（**必须纯 ASCII**） |
| MySQL | `localhost:3306`，库 `contract_review`，`root / <你的密码>` |
| Redis | `localhost:6379`，`requirepass <你的密码>` |
| Docker | **不需要**，用本机 Windows 服务 |
| 控制台编码 | `HKCU\Console` 已设 UTF-8，中文不再乱码 |

> [!note] 系统 PATH 里仍残留 jdk-1.8 / jdk-19
> 清理需要管理员权限，**未处理且不影响构建**：Maven 认 `JAVA_HOME`，项目内已用 `.mvn/jvm.config` 再锁一层。
> 副作用：直接敲 `java -version` 可能仍显示 1.8。要用 JDK 21 就跑 `.\run-dev.ps1`。

## 已生成的工程文件

```
pom.xml                                            依赖收敛（刻意不装 6 个框架）
run-dev.ps1                                        一键启动（自动找 JDK、护额度）
README.md                                          启动步骤与已知技术债
.gitignore                                         排除敏感配置
.mvn/jvm.config                                    锁定 JDK 21
src/main/java/.../ContractReviewApplication.java   启动类（注释写明模块边界纪律）
src/main/java/.../config/SecurityConfig.java       BCrypt（Day 1 占位版，T-004 会改造）
src/main/java/.../health/HealthController.java     自检端点，暴露 AI 通道状态
src/main/resources/application.yml                 主配置
src/main/resources/application-dev.yml             本机 MySQL + 自动建表
src/main/resources/db/schema.sql                   建表（可重复执行）
src/main/resources/db/data.sql                     2 租户 + 3 演示账号
src/test/resources/application-test.yml            H2，禁用 Redis，AI 关闭
src/test/resources/db/schema-h2.sql                H2 版建表
src/test/java/.../ContractReviewApplicationTests.java  骨架自检（2 个用例）
```

## 下一步（按顺序）

1. **T-013 证据对齐算法** ← 项目核心，严格 TDD。AI 结论必须能在原文定位，否则不许进报告
2. **T-014** DeepSeek 客户端 + Mock 开关 + 成本计数器
3. **T-019** 降级路径：AI 挂了规则链照常可用
4. **前端最小页面闭环**（Day 5）

## 踩过的坑（面试素材，别丢）

| 坑 | 教训 |
| --- | --- |
| JDK 21 从 Adoptium 下载被重置（`curl: (56) Recv failure`） | 同一地址重试即成功。**下载失败先重试/换镜像，别急着换方案** |
| `.mvn/jvm.config` 写了中文注释 → Maven 报 `ClassNotFoundException: #` | 该文件由 JVM 直接读取，**必须纯 ASCII** |
| 控制台中文乱码 | 用 `HEX()` 查字节，确认是"显示错"还是"存错"，别改错地方 |
| H2 能过不等于 MySQL 能过 | 建表脚本已在真实 MySQL 上单独验证一次 |
| **首次提交时把数据库密码写进了 8 处文档与脚本** | 改文件再提交**不够**——旧提交历史里仍有密码。未推送时用 `git update-ref -d refs/heads/main` + `git reset` 清历史，重新提交 |
| `github.com:443` 被封锁，push 超时 | 改走 `ssh.github.com:443`（配 `~/.ssh/config`）。**先诊断是哪个端口不通，再换方案** |
| `.ps1` 中文注释导致脚本语法错误 | Windows PowerShell 5.1 读无 BOM 的 UTF-8 文件时按 GBK 解码，**脚本必须存为 UTF-8 with BOM** |
| 执行策略禁止运行脚本 | 用 `powershell -ExecutionPolicy Bypass -File .\push.ps1` |
| 架构测试扫不到 Mapper（`ClassPathScanningCandidateComponentProvider` 默认排除接口） | **"扫到 0 个"会假绿**，所以另写断言确认确实扫到了 |
| `String.formatted()` 处理含 `%PDF` 的模板抛 `UnknownFormatConversionException` | `%` 在格式化字符串里是格式符，**含 `%` 的模板不要交给 `formatted()`** |
| **分片编辑 YAML 导致顶层 `spring:` 出现两次** | 配置类文件**整份重写**，不要做片段插入。测试直接报出 50 个错误，立刻抓到 |
| **MockMvc 测静态资源拿到空字符串** | MockMvc **不加载 static 目录**。要测静态页面须用 `RANDOM_PORT` + `TestRestTemplate` 起真实容器 |
| **用终端判断中文编码，得出互相矛盾的结论** | 最终用 Java 字节级判定 `contains == true` 才作数。**判断编码要看字节，不要看终端的脸** |
| **`Out-File -Encoding ASCII` 把检查脚本里的中文字面量替换成了 `?`** | 之后 `"中文" in html` 永远为 False，白白怀疑了半天页面。**写含中文的临时脚本要用 UTF-8，不要用 ASCII** |
| **金额正则把日期里的年份当成合同金额** | 纯数字匹配在"签订日期：2026-01-01"里抓到了 2026。**区分金额与日期靠上下文关键词，不靠数字形状** |
| 中文大写金额解析：先清洗再判断纯数字，顺序反了 | `￥1,280,000元` 解析失败。**判断必须在剥掉货币符号与单位之后做** |
| 外部小工具维护 PDFBox classpath：缺 `pdfbox-io`、TTC 字体不能直接 load | **不要在应用外面另维护依赖**。改为应用内 `@Profile({"dev","test"})` 端点，复用已有依赖 |
| PDF 里的 Helvetica **不支持中文** | 写入中文抛 `U+7532 ('.notdef') is not available`。测试构造 PDF 必须嵌 CJK 字体（且要用 .ttf 不是 .ttc） |
| `TestRestTemplate` 对 401+POST 抛 "cannot retry ... in streaming mode" | 那是客户端行为不是服务端缺陷。测错误状态码改用 JDK `HttpClient` |
| 断言"缺少的条款应有位置" | 缺失的条款在文中**不存在**，不可能有位置。**不给位置比给错位置好** |

## 关键决策速查（面试会问）

| 编号 | 决策 | 一句话理由 |
| --- | --- | --- |
| D-01 | 模块化单体，不拆微服务 | 没学过 + 5 天期限，边界清晰比拆得多更重要 |
| D-03 | 证据对齐独立成层 | 模型会幻觉出不存在的条款，必须机器校验 |
| D-04 | 规则与 AI 互不调用 | 确定性判断不该付概率代价 |
| D-07 | `RestClient` 直连 DeepSeek | 只有一个供应商，抽象层是负债 |
| D-08 | 不用 Flyway | 没学过，5 天不值得；**已登记为技术债** |
| D-12 | AI 默认走 Mock + 次数上限 | 额度仅 15 元，真正的风险是调试死循环 |

## 不能砍的三条底线

> 无论进度多赶，这三项必须完成——它们是项目唯一区别于"套壳 AI 项目"的理由。

1. **T-013 证据对齐**：AI 结论必须能在原文定位，否则不许进报告
2. **T-019 降级路径**：AI 挂了，规则与人工流程照常可用
3. **T-018 审计只追加**：复核记录不可覆盖
