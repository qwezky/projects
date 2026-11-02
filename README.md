# 个人项目集（脱敏版）

这里是我做过的一些项目的**脱敏副本**：去掉了主机名、域名、IP、令牌、账号和真实个人数据，
只保留代码、结构、设计取舍与实测结论。所有示例地址都是 `example.com` / `2001:db8::/32`
这类文档专用值，直接照抄跑不起来，需要换成自己的环境。

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
| [android-transfer](android-transfer/) | Kotlin / Compose / JSch | ~2.8k 行 | 手机 ↔ 服务器 SFTP 双向传输；局域网只读 HTTP 分享；按网络类型选路 |

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
| android-transfer | `./gradlew --offline :app:assembleDebug` 构建通过（JDK 17 / SDK 36） |

## 说明

- 这些代码是按「我自己的用法」写的，不承诺通用；接口与配置以各项目 README 为准。
- 仓库里的用户名、域名、IPv6、token 都是占位符；原始值已全部替换。
- 没有附带开源许可证；如需转载或复用，请先联系我。
