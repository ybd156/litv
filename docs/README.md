# 文档索引

## 分类规则

只按一条线分：**这份文档描述的是现状，还是历史。**

- **现状**（代码改了就跟着改，否则就是错的）：`design/`、`review/STATUS.md`、`security/`
- **历史**（记录当时发生了什么，**永远不改写**）：`postmortem/`、`research/`、`CHANGELOG.md`

判据：如果文档里写"已实现/已完成"，它属于现状，必须能被代码核对；
如果写"当时为什么这么决定"，它属于历史，改动它就等于伪造记录。

## 事实源优先级

冲突时按这个顺序信：

1. **代码**（`server/` `admin/` `android/` `ios/`）——唯一真相
2. `AGENTS.md`——规则与边界
3. `docs/design/`——结构与契约
4. `docs/review/STATUS.md`——哪项做完了、哪项没有
5. 其它一切，包括 `CHANGELOG.md` 和 `research/`

## 目录

| 路径 | 内容 | 性质 |
|---|---|---|
| [`design/00-总体方案.md`](design/00-总体方案.md) | 模块职责、三条主链路、CI 八条工作流 | 现状 |
| [`design/01-API.md`](design/01-API.md) | 信封、错误码、认证三档、92 条路由表（脚本抽取，带行号） | 现状 |
| [`design/02-iOS构建现状.md`](design/02-iOS构建现状.md) | iOS 工程结构、会话测试目标、C05 未实现的具体位置 | 现状 |
| [`review/STATUS.md`](review/STATUS.md) | **唯一**的评审项状态源：已确认 / 待验证 / 未复核 | 现状 |
| [`postmortem/POSTMORTEM_2026-11-12_tv-input.md`](postmortem/POSTMORTEM_2026-11-12_tv-input.md) | 电视输入事故复盘 | 历史 |
| [`security/hardening-2026-11.md`](security/hardening-2026-11.md) | 安全加固记录 | 历史 |
| [`security/uninstall-callback.md`](security/uninstall-callback.md) | 卸载链路威胁模型 | 现状 |
| [`research/01-fnos-fpk.md`](research/01-fnos-fpk.md) | fnOS fpk 规范调研 | 历史 |
| [`research/02-stack-and-oss.md`](research/02-stack-and-oss.md) | 技术选型与 License 调研 | 历史 |
| [`release-notes.md`](release-notes.md) | 发版说明 | 现状 |

## 两条硬约束

1. **`release-notes.md` 不能移动或改名**。`.github/workflows/release.yml:463` 直接 `cp docs/release-notes.md`，
   挪了发版就断。

2. **历史文档里的链接可能悬空**。例如 `CHANGELOG.md:729` 指向
   `docs/review/AI_PROJECT_REVIEW_2026-11-02.md`，那份已按"重写现状文档"的要求删除。
   要读它从 git 历史取：

   ```bash
   git show 861269f:docs/review/AI_PROJECT_REVIEW_2026-11-02.md
   ```

   历史条目一律不改写——改了就分不清"当时真的做了"还是"后来补的"。

## 新增文档时

- 现状类放 `design/` 或 `review/`，并在本索引登记。
- 事故复盘放 `postmortem/`，文件名带日期，写清触发条件、根因、修复、残留风险。
- 能机械生成的就机械生成（见 `01-API.md`），手写表格一定会过期。
