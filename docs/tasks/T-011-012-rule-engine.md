---
title: T-011 / T-012 规则引擎 · 结果三分 · 规则实现
aliases:
  - 当前任务卡
tags:
  - 项目
  - 任务
  - TDD
status: 已验收
---

# T-011 / T-012 规则引擎 · 结果三分 · 规则实现

## 任务边界

- 所属模块：[rule](../modules/rule.md)
- 对应需求/验收：F-04、**A-04**（规则结论可复现）
- 目标行为：
  - 对确定性可判定的问题给出结论，**结论可复现**且**不联网**
  - 结果必须三分：命中 / 通过 / **无法判定**
  - 结论落库，可在 API 与页面上区分三态
- 本任务不做：证据对齐算法（T-013）、AI 审查（T-014）
- 前置依赖：T-009 / T-010（需要正文）✅
- 涉及的不变式：**I-03**（规则结论确定性可复现）

## 本任务最重要的一个设计：结果三分

反例是很自然的写法——规则返回 `boolean`：

```java
if (element == null) return false;   // ← 严重的错误
```

这会把"要素抽不到"当成"这条规则通过了"。用户看到的结论是"合同合规"，
而真相是"系统根本不知道"。**在信息不足时输出"合规"，比报错危险得多。**

因此：

| 结果 | 含义 | 用户可见 | 报告地位 |
| --- | --- | --- | --- |
| `HIT` | 确定存在该问题 | 问题条目 | 正文 |
| `PASS` | 确定不存在该问题 | 不显示（或折叠） | 不进正文 |
| **`UNDETERMINED`** | **输入不足，无法判定** | **待人工确认** | **必须进正文** |

配套的三条实现约束：

1. `ElementLookup` 用 `Optional` 而不是返回 null——**逼规则作者面对缺失**
2. `ElementStatus` 区分"有值但置信度低"（能算）与"无值"（不能算）
3. 无法判定时必须写明**缺哪个字段**，不能只说"数据不足"

## 验收示例

### 正常路径

- 给定：要素齐全、条款完整的虚构合同
- 当：`POST /api/contracts/{id}/rule-check`
- 则：4 条规则全部给出确定结论，状态推进到 `RULE_CHECKED`

### 异常路径

| 情况 | 期望 |
| --- | --- |
| 要素缺失（无金额/日期/主体） | 对应规则 `UNDETERMINED`，**并写明缺失字段名** |
| **持久化后** | `UNDETERMINED` 仍是 `UNDETERMINED`，**不得被存成 `PASS`** |
| 合同未解析成功 | 400 `PARSE_FAILED`，**拒绝在无正文时执行** |
| 大写金额含角分无法解析 | `UNDETERMINED`，**不按 0 处理** |
| 金额要素多来源冲突 | `UNDETERMINED`，**不替人挑一个** |
| 某条规则自身抛异常 | 该条标 `UNDETERMINED` + `errorMessage`，**其余规则继续** |
| 规则编码重复 | **启动即失败**，不允许运行时结论混在一起 |
| 缺少的条款 | `HIT` 但**不带位置**（想指的地方不存在） |

### 明确不该发生的事

- 缺失被当成通过（在任何一层：规则、引擎、持久化、API）
- 规则出错被静默跳过
- 规则内部读系统时钟或联网
- 重复校验累积旧结论

## 实现清单

| 文件 | 职责 |
| --- | --- |
| `rule/domain/RuleResult.java` | **三态枚举**，本模块的核心 |
| `rule/domain/ElementStatus.java` | `CONFIRMED`/`LOW_CONFIDENCE`（有值）vs `UNKNOWN`/`CONFLICT`（无值） |
| `rule/domain/ElementField.java` | 字段键名枚举，避免字符串拼错导致安静地取不到值 |
| `rule/domain/ElementLookup.java` | 取值接口，**用 `Optional` 逼出缺失处理** |
| `rule/domain/RuleContext.java` | 执行输入；**时钟显式注入**，规则不得自读 |
| `rule/domain/Rule.java` | 规则契约，注释写死四条纪律 |
| `rule/domain/RuleOutcome.java` | 单条规则的原始结果 |
| `rule/domain/RuleFinding.java` | 补齐元信息后的结论，含 `errorMessage` |
| `rule/engine/RuleRegistry.java` | 规则注册 + **编码唯一性校验（启动失败）** |
| `rule/engine/RuleEngine.java` | 遍历、汇合、**单条出错隔离** |
| `rule/engine/MapElementLookup.java` | 通用实现，供测试与抽取结果适配 |
| `rule/support/ChineseAmountParser.java` | 中文大写金额解析 |
| `rule/support/TextSearch.java` | 证据定位，**找不到就返回空** |
| `rule/rules/AmountConsistencyRule.java` | 金额大小写一致 |
| `rule/rules/DateOrderRule.java` | 签署 ≤ 生效 ≤ 到期 |
| `rule/rules/RequiredClauseRule.java` | 争议解决 / 付款 / 违约条款存在性 |
| `rule/rules/PartyConsistencyRule.java` | 主体名称与正文一致 |
| `rule/mapper/RuleFindingMapper.java` | 落库，全部 SQL 带 `tenant_id` |
| `rule/RuleCheckService.java` | 编排：取正文 → 抽要素 → 跑规则 → 落库 |
| `rule/RuleCheckController.java` | `POST /rule-check`、`GET /findings` |
| `extract/DeterministicElementExtractor.java` | 纯正则抽要素（零 AI 成本） |
| `web/SampleContractController.java` | 生成示例 PDF（仅 dev/test），供调试台用 |

## TDD 记录

### 1. 失败测试（RED）

先写 `RuleEngineTest`（11 例）与 `RulesTest`（21 例），再实现引擎与规则。

其中最关键的一条断言：

```java
assertThat(result.undeterminedCount()).isEqualTo(1);
assertThat(result.passCount()).isZero();   // ← 若被折叠成 PASS，这里会失败
```

### 2. 最小实现（GREEN）

按上表实现。刻意**没有**做：规则集可视化配置、证据对齐（T-013）。

### 3. 必要整理（REFACTOR）

- 引擎与规则分离：新增规则只需加一个 `@Component`，不改引擎。
- 抽要素独立成 `extract` 包：它是"确定性抽取"，与"确定性判断"是两件事，
  混在 rule 里会让两个模块的边界说不清。
- 示例 PDF 生成放进应用（`@Profile({"dev","test"})`）而不是外部小工具，
  见下方"踩坑"。

### 4. 验证结果

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| 单元测试 | ✅ | 引擎 11 + 规则 21 + 金额解析 12 |
| 集成测试 | ✅ | `RuleCheckServiceTest` 9 例 |
| **合计** | ✅ | `Tests run: 153, Failures: 0, Errors: 0` / BUILD SUCCESS |
| **真实 MySQL 端到端** | ✅ | 见下表 |

#### 真实 MySQL 端到端结果

| 场景 | 结果 |
| --- | --- |
| 要素齐全的合同 | 4 条全部 `PASS`，要素 7 个全部抽出 |
| 金额大小写一致 | `PASS`，依据写明"小写 128000.00 与大写 128000 一致" |
| 日期顺序 | `PASS`，并说明**比较了哪三对日期** |
| **要素缺失的合同** | **3 条 `UNDETERMINED` + 1 条 `PASS`**，缺失字段逐个列出 |
| **落库后三态** | MySQL 中 `UNDETERMINED` 与 `PASS` **分别存储**，未被混淆 |
| 重复校验 | 删除旧 4 条写新 4 条，**库中仍为 4 行**（不是 8） |
| 两次结论一致性 | 逐条相同 ✔ |
| 未解析就校验 | 400 `PARSE_FAILED`，"合同尚未解析成功，无可用正文" |
| 跨租户校验 | 404；读结论返回空列表 |
| 删除合同 | 级联清理规则结论，`rule_finding` 归零 |

### 5. 全量回归

- `mvn test` → 153/153。

## 实施日志（短期）

本任务踩了 **3 个坑**，其中第 1 个是真 bug：

1. **金额正则把日期里的年份当成了合同金额。**
   第一版用纯数字匹配，结果在"签订日期：2026-01-01"里把 **2026 当成金额**。
   修复：金额必须**锚定在金额关键词之后**（合同金额/价款/总价/金额…），
   并额外过滤 1900~2099 的四位整数。
   **教训：区分"金额"与"日期"靠的是上下文关键词，不是数字本身的形状。**

2. **中文大写金额解析里"先清洗再判断"的顺序反了。**
   `￥1,280,000元` 解析失败——因为我先把千分位逗号去掉，
   再判断"是否纯数字"时拿到的却是 `1280000元`（含"元"），于是落到中文解析分支而失败。
   修复：判断纯数字必须在**剥掉货币符号与单位之后**。

3. **示例 PDF 生成工具的字体问题。**
   - PDFBox 3 把 `IOUtils` 拆到了独立的 `pdfbox-io` 模块，外部工具少这个 jar 就 `NoClassDefFoundError`
   - `msyh.ttc` 是**字体集合**，`PDType0Font.load` 直接抛 `'head' table is mandatory`

   两次都说明同一件事：**在应用外面另维护一套 PDFBox classpath 是自找麻烦。**
   改为在应用内加一个 `@Profile({"dev","test"})` 的示例 PDF 端点后，
   复用已有依赖与字体解析逻辑，问题消失。

此外还修正了 **2 个测试断言错误**（代码是对的）：

- 我断言金额命中时 `charStart == 0`，实际是 **5**——前缀"合同金额："正好 5 个字符，定位准确。
- 我断言"缺少必备条款"时应当带位置。但**缺失的条款在文中根本不存在**，
  不可能给出位置；硬塞一个（例如指向其他关键字）会让人工跳转到无关地方。
  **不给位置比给错位置好。**

## 完成结论

- 状态：**已验收**

### 面试可讲点

1. **为什么规则结果要三态而不是布尔？** → 布尔只有"有问题/没问题"，而"抽不到要素"是第三种情况。如果把缺失当通过，系统会在信息不足时输出"合同合规"——**这比报错危险得多**。我为此专门写了断言锁住它，也在持久化层复验了一次。
2. **怎么保证"无法判定"不被悄悄折叠成"通过"？** → 三道：`ElementLookup` 用 `Optional` 逼出缺失处理；引擎统计里把 `undeterminedCount` 单独暴露；落库时 `result` 是独立列，还有一条集成测试专门断言库里 `UNDETERMINED` 的条数。
3. **规则为什么不能读系统时间？** → 读了就不可复现：同一份合同今天判"未过期"、明天判"已过期"，测试也没法固定。我把时钟放进 `RuleContext` 由调用方注入，**时间也就成了一个显式的输入**。
4. **一条规则写错了会怎样？** → 它自己被标记为 `UNDETERMINED` + `errorMessage`（不是 `PASS`，因为它没有产出判断），其他规则继续执行。**规则编码重复甚至会在启动时就失败**——那种错误在运行时很难发现。
5. **金额正则有什么坑？** → 我第一版用纯数字匹配，把日期里的 **2026 当成了合同金额**。修复是让金额必须出现在"合同金额/价款/总价"等关键词之后。**区分金额与日期靠上下文，不靠数字形状。**
6. **中文大写金额解析为什么"失败"的情况这么多？** → 因为它涉及角分（十进制细分而非位值）、多种同义写法。我明确划定范围：只支持到"元"，遇到角分返回空让规则转人工。**宁可转人工，也不要给出一个可能错的金额。**
7. **规则集的严重级别从哪来？** → 目前凭业务常识设定，**没有真实法务依据**。这是已知弱点：真实系统的级别应当来自业务方的风险清单。面试时我会主动说明这一点。
8. **规则输出为什么要排序？** → 按规则编码排序保证同样输入两次执行的结论**逐条同序**。否则"同输入同输出"会在顺序上被破坏，对比两次结果时会看到无意义的差异。

### 新增/变更的错误码与接口

- 无新增错误码；复用 `PARSE_FAILED`、`CONTRACT_NOT_FOUND`
- 新增接口：`POST /api/contracts/{id}/rule-check`、`GET /api/contracts/{id}/findings?onlyHits=`
- 新增调试端点（仅 dev/test）：`GET /api/debug/sample-contract.pdf?template=well-formed|sparse`

### 需要回写的长期事实

| 目标 | 内容 |
| --- | --- |
| [rule 模块笔记](../modules/rule.md) | 三态设计、时钟注入、出错隔离、上下文锚定 |
| [02 架构](../02-architecture.md) | D-24~D-28 |
| [README](../../README.md) | 新增接口与调试端点 |
| [08 面试脚本](../08-interview-and-demo-script.md) | 三态设计、金额正则的坑 |

### 后续任务

T-013（**证据对齐算法**，项目核心）。

## 原子提交记录

| 提交信息 | 说明 |
| --- | --- |
| `feat(rule): three-state rule engine that never treats unknown as pass` | T-011 |
| `feat(rule): add amount, date, clause and party consistency rules` | T-012 |
| `feat(extract): deterministic regex element extraction` | 支撑规则输入 |
| `fix(extract): anchor amount extraction to amount keywords` | 缺陷 1 |
| `fix(rule): check plain digits after stripping currency symbols` | 缺陷 2 |
| `feat(web): expose sample contract PDF for dev and test profiles` | 缺陷 3 的解法 |

%%
本卡已完成验收。三态语义与注入纪律已回写 rule 模块笔记。
终端的原始输出未长期保存。
%%
