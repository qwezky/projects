# 个人项目集

这里是我做过的一些项目，保留了代码、结构、设计取舍与实测结论。示例地址使用
`example.com` / `2001:db8::/32` 这类文档专用值，直接照抄跑不起来，需要换成自己的环境。

仓库：`git@github.com:qwezky/projects.git`（<https://github.com/qwezky/projects>）。
各项目没有独立仓库，都是本仓库下的一个目录；版本以 tag 标注（例如 `dsh-client` 的 `v3.2.4`）。

每个目录都能单独读：先看该目录的 `README.md`。

## 项目

| 项目 | 技术栈 | 规模 | 一句话 |
|---|---|---|---|
| [inbox-archiver](inbox-archiver/) | Python 3.11+ / sqlite / 本地 LLM + OCR | ~4.4k 行 | 手机「存档区」上传 → 本地模型 OCR、归档、按天抽待办；多路抽取 + 一致度聚类 + 确定性核对 |
| [dsysmon](dsysmon/) | Python 标准库 | ~2.6k 行 | 单文件服务器监测：CPU/内存/电池/磁盘/网络/风扇/温度/GPU/日志，终端与网页同一份数据 |
| [thermal](thermal/) | Python / shell / systemd | ~450 行 | 从 EC RAM 只读风扇转速并常驻暴露；可重复的热负载基线 |
| [dsh-client](dsh-client/) | Java 17 / Swing / JSch | ~3.6k 行 | 用 SSH 本地转发把只监听 loopback 的 Web 界面搬到本机；支持 SSH over WebSocket |
| [grounding-eval](grounding-eval/) | Python / PyTorch / MiniCheck | ~700 行 | 判断「抽出的待办有没有材料依据」：联合翻译 + 英文 MiniCheck，只打标不删除 |
| [notice-radar](notice-radar/) | Python 标准库 | ~2.5k 行 | 校园通知聚合：抓取 → 正文/PDF/图片 OCR → 模型打分 → 日报 → webhook |
| [android-transfer](android-transfer/) | Kotlin / Compose / JSch | ~4.6k 行 | 手机 ↔ 服务器双向传文件：SFTP 直连 + 隧道里的 HTTP 兜底；目录缓存、文件名搜索、断点续传 |
| [android-journal](android-journal/) | Kotlin / Compose / JSch | ~5.1k 行 | 手机 → 服务器「存档投递」：文本/图片进收件目录 → 触发归档 → 按日期看待办；SFTP 与 HTTP 双通道 |

## 为什么值得看

- **不是 demo**：每个项目都在真实环境里跑过一段时间，README 里保留的是当时踩坑后的结论
  （比如「为什么小模型当裁判比当选手更差」「为什么预热的温度判据必须加时间下限」
  「为什么高 RTT 下 SSH 反而比网页慢」）。
- **有测试**：`inbox-archiver` 带 206 项离线自测；`android-transfer` 带单元测试与
  可选的端到端测试；`dsh-client` 可编译出可运行 jar。
- **取舍写清楚了**：每个项目都有「已知边界」，说明它不做什么、在哪里会错。

## 目录约定

```
<project>/
  README.md     项目说明（架构 / 用法 / 设计取舍 / 边界）
  ...           源码
```

运行时产物（`data/`、`run/`、`dist/`、`build/`、`__pycache__/`、`local.properties`）都不入库。

## 验证状态

| 项目 | 验证 |
|---|---|
| inbox-archiver | `python3 tests/selftest.py` → 206 通过 / 0 失败 |
| dsysmon | `python3 dsysmon.py --no-color`、`--json` 正常 |
| thermal | `py_compile` / `bash -n` |
| dsh-client | `build-jar.sh` 编译通过（JDK 17 + JSch） |
| grounding-eval | `py_compile`；需 MiniCheck 权重才能跑 |
| notice-radar | `python3 notice_radar.py --help` / 空库 `stats` 正常 |
| android-transfer | `./gradlew :app:compileDebugKotlin` 构建通过（JDK 17 / SDK 36） |
| android-journal | `./gradlew :app:compileDebugKotlin` 构建通过（JDK 17 / SDK 36） |

## 说明

- 这些代码是按「我自己的用法」写的，不承诺通用；接口与配置以各项目 README 为准。
- 仓库里的用户名、域名、IPv6、token 都是占位符，请替换成自己的配置。
- 使用 [PolyForm Noncommercial 1.0.0](LICENSE)：个人、学习、研究、非营利可用；商业用途需另行授权。
