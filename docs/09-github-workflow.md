# 上传到 GitHub 指南

> 本文档记录本项目的版本管理方式，以及**本机网络环境下的特殊处理**。
> 换机器时按"第二部分"重新配置即可。

## 一、日常上传（最常用）

每次写完一段代码，在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\push.ps1 -Message "feat(auth): add JWT login"
```

> [!warning] 必须加 `-ExecutionPolicy Bypass`
> 本机 PowerShell 执行策略禁止直接运行脚本（`.\push.ps1` 会报"禁止运行脚本"）。
> 用 `powershell -ExecutionPolicy Bypass -File .\push.ps1` 是**当前这一次**绕过，不改系统设置，也不需要管理员权限。

### 当前仓库信息

| 项 | 值 |
| --- | --- |
| 仓库 | <https://github.com/zbfm123/xm> |
| 远端 | `git@github.com:zbfm123/xm.git`（SSH，走 443 端口） |
| 提交邮箱 | `chen2686195184@126.com`（与 GitHub 账号绑定邮箱一致，提交能正确归属） |
| 默认分支 | `main` |

脚本会依次做五件事：检查远端 → 暂存改动 → **扫描敏感信息** → 提交 → 推送。

### 为什么要用脚本而不是直接敲 git

1. **敏感信息拦截**：提交前扫描明文口令、API Key、私钥、常见弱口令。凭据泄露是公开仓库最常见也最致命的失误。
2. **避免忘提交**：一条命令完成 add / commit / push，不会出现"改了但没推"。
3. **首次推送自动设上游**：不需要记 `-u origin main`。

### 常用参数

| 参数 | 用途 |
| --- | --- |
| `-Message "..."` | 必填，提交信息 |
| `-NoPush` | 只提交不推送，攒几个提交后一起推 |
| `-SkipSecretCheck` | 跳过敏感信息扫描（确认无误时用） |

### 提交信息怎么写

沿用 Conventional Commits，**本仓库用英文**（面试官会看提交历史）：

| 前缀 | 用途 | 示例 |
| --- | --- | --- |
| `feat(scope)` | 新功能 | `feat(parse): extract text from PDF with coordinate mapping` |
| `fix(scope)` | 修 bug | `fix(evidence): map normalized offsets back to source coordinates` |
| `test(scope)` | 只加测试 | `test(rule): cover amount mismatch boundary cases` |
| `refactor(scope)` | 重构 | `refactor(workflow): extract state transition table` |
| `docs(scope)` | 文档 | `docs(specs): define evidence alignment levels` |
| `chore` | 杂项 | `chore(deps): bump spring boot to 3.3.5` |

> [!tip] 为什么要坚持原子提交
> 面试时你可以打开提交历史说："这一串是同一个任务的演进——先写失败测试，再最小实现，最后补边界用例。"
> **提交历史本身就是证据**，比口述"我用了 TDD"可信得多。

## 二、首次配置（每台机器做一次）

### 1. 生成 SSH 密钥

```powershell
ssh-keygen -t ed25519 -C "2686195184@qq.com" -f "$env:USERPROFILE\.ssh\id_ed25519"
```

### 2. 配置走 443 端口（**本机必需**）

本机 `github.com:443` 被封锁，但 `ssh.github.com:443` 可用。因此
`~/.ssh/config` 中把 `github.com` 映射到 `ssh.github.com:443`：

```
# GitHub over SSH on port 443
# Local machine: port 22 and github.com:443 are both blocked, but ssh.github.com:443 works
Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile ~/.ssh/id_ed25519
    StrictHostKeyChecking accept-new
```

### 3. 把公钥加到 GitHub

```powershell
Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub"
```

复制输出，粘贴到 <https://github.com/settings/keys>（New SSH key）。

> [!warning] github.com 网页打不开怎么办
> 加公钥需要访问网页。若打不开，两个办法：
> 1. 用手机流量开热点，或换网络访问一次（只需一次）
> 2. 改用 **Gitee**：`gitee.com` 本机可直连，且中文简历同样认可。步骤相同，
>    只是把 URL 换成 `git@gitee.com:用户名/仓库名.git`

### 4. 验证连通

```powershell
ssh -T git@github.com
```

期望输出含 `Hi 用户名! You've successfully authenticated`。
（退出码为 1 是正常的，GitHub 不提供 shell。）

### 5. 建仓库并关联

在 GitHub 建一个**空仓库**，**不要勾选** README / .gitignore / license
（勾了会生成一个本地没有的提交，首次推送会被拒绝）。

```powershell
git remote add origin git@github.com:你的用户名/仓库名.git
```

### 6. 首次推送

```powershell
powershell -ExecutionPolicy Bypass -File .\push.ps1 -Message "chore: initial commit"
```

## 三、本机环境踩过的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `git push` 超时 | `github.com:443` 被封锁 | SSH 改走 `ssh.github.com:443`（见上文配置） |
| 脚本报 `无法加载文件...禁止运行脚本` | PowerShell 执行策略限制 | 用 `powershell -ExecutionPolicy Bypass -File .\push.ps1` |
| `.ps1` 中文注释导致语法错误 | **Windows PowerShell 5.1 读取无 BOM 的 UTF-8 文件时按 GBK 解码** | 脚本必须保存为 **UTF-8 with BOM**，且不要移除 BOM |
| `push` 被拒绝（non-fast-forward） | 建仓库时勾了 README | `git pull --rebase origin main` 后再 push |
| `.mvn/jvm.config` 报 `ClassNotFoundException: #` | 该文件由 JVM 直接读取，中文注释被按 GBK 解析 | **必须纯 ASCII** |

## 四、敏感信息红线

| 内容 | 存放位置 |
| --- | --- |
| MySQL / Redis 密码 | `application-local.yml`（**已被 .gitignore 排除**）或环境变量 |
| DeepSeek API Key | 只走环境变量 `DEEPSEEK_API_KEY` |
| 真实合同原文、真实姓名、真实公司 | **任何时候都不进仓库**，演示一律用虚构数据 |
| 日志、截图、终端输出 | 放当前任务卡，任务结束前清理 |

> [!important] 如果不小心提交了密码
> **改文件再提交一次是不够的**——旧提交的历史里仍有密码。正确做法：
> ```powershell
> # 仓库还没推送到远端时（最省事）
> git update-ref -d refs/heads/main
> git reset
> git add -A
> git commit -F 消息文件
> ```
> 本项目首次提交时就发生过这件事（配置里带了数据库密码），
> 因为还没推送，用上面的方式在本地清掉了历史，记录见
> [PROGRESS](PROGRESS.md#踩过的坑面试素材别丢)。
