# inbox-archiver —— 手机随手记 → 本地模型整理归档

手机上把文本/图片丢进一个「存档区」，服务器用**本机模型**把图片 OCR 成文字、
按规则归档，再把材料里**带日期的待办**抽出来按天归好，之后可以按任意时间段回读。

```
                                      ┌─ 文本 ─→ records/<原名>.txt
手机「传输助手」存档区 ──SFTP──→ raw/ ┤
      │                               └─ 图片 ─→ OCR ─→ records/<名字>/{原图, text.md}
      └──SSH `archive-trigger`──→ 服务端开始一轮归档
                                                    │
                                                    └─ 本地 LLM 抽待办 ─→ todos/年.月.日/
```

- 三个区：`raw/`（原文件）、`records/`（档案）、`todos/`（事项）
- 全部推理在本机（或自己配的远程模型），不依赖云服务
- **没有常驻进程**：手机触发一次就跑一轮，跑完退出

## 快速开始

```bash
python3 bin/inbox doctor          # 环境自检
python3 bin/inbox status          # raw 里有什么、模型在不在
python3 bin/inbox run --dry-run   # 演练：只说打算怎么归档
python3 bin/inbox run             # 真跑一轮
python3 bin/inbox todos --last 7  # 最近 7 天的待办
```

把命令放进 PATH：

```bash
ln -sf "$(pwd)/bin/inbox" ~/.local/bin/inbox
```

依赖：Python 3.11+（用到 `tomllib`）。图片 OCR 与待办抽取需要额外的模型服务：

| 服务 | 默认端口 | 角色 | 说明 |
|---|---|---|---|
| PaddleOCR-VL（或任意兼容 OCR 服务） | 8083 | 图片转文字 | 只要求一个 `/` 接口按 `ocr_prompt` 返回文本 |
| llama.cpp server（OpenAI 兼容） | 8080 | 抽待办 | 小模型即可；也可换成任意 OpenAI 兼容端点 |

模型服务可以手动先起，也可以让 `config.toml` 的 `ocr_start_script` /
`~/.local/bin/llm` 负责拉起。**没有模型也能用**：`ocr_mode = "off"` 时图片只打包不识别，
归档功能不受影响。

## 归档规则

### raw/ → records/

| 类型 | 去向 |
|---|---|
| 文本（txt/md/csv/json/yaml…） | `records/<原名>.<ext>`，原样放，不改名（同名加 `-2`） |
| 图片（jpg/png/webp/heic…） | 先 OCR，再打包成 `records/<原名去后缀>/`：**原图 + text.md** |
| 视频/APK/压缩包等 | 存档区本不该有；按 `foreign_action` 处理（默认报错留在 raw） |
| 内容重复（sha256 相同） | 只留一份，副本进 `raw/_duplicate/` |

`text.md` 正文是 OCR 原文（不改写），头部带来源文件名、大小、sha256、日期、OCR 引擎。

### records/ → todos/

**每件事单开一个文件**，日期目录里**只记地址**：

```
todos/
  items/
    20261031-参加社团交流活动.md
    20261015~20261018-完成小组调研报告.md
    _已撤下/
  2026.10.31/
    items.md      ← 只有地址：- [标题](<../items/xxx.md>)
    items.txt     ← 只有地址：../items/xxx.md（一行一个）
  index.jsonl     ← 全部事项索引
```

抽取规则：

- **只认有日期的**。没写日期、也推算不出来的 → 不入 todos（内容仍在 records 里）
- 单日事项进那一天；跨多日事项在区间内**每一天**都指向同一份事项文件
- 相对说法（今天/明天/下周三/月底）按**材料日期**换算成绝对日期
- 事项文件带 front-matter（task / start_date / end_date / votes / passes /
  evidence_grounded / source_name / record_path），`## 备注` 之后是用户自己的地盘，
  重建时原样保留

### 时间要绑在「它真正描述的那件事」上

同一份通知里常同时有「报名」和「活动」，日期只属于其中一个：

| 材料里的说法 | 那天该做什么 |
|---|---|
| 将于 X 举行 / X 开始 / X 当天 | **参加/出席**（task 写「参加…」） |
| X 前报名 / 报名截止 X | **报名** |
| X 前提交 / X 截止 | **提交** |
| 活动有日期、报名没写日期 | 只输出「参加…」一条，不拿举行日硬凑报名 |

另外两条：**不替当事人做材料没做的选择**（给 A/B 组就保留选项）、
**不补材料里没有的地点/金额/材料清单**。

## 准确率是怎么换来的

一份材料不是问一次模型就完事，而是**多路抽取 → 一致度聚类 → 确定性核对**：

| 环节 | 做法 | 为什么 |
|---|---|---|
| 多路抽取 | 主提示词跑 N 路（第 1 路 temp=0 求稳）＋ 1 路**换切分**（先抄「时间点→谓语」再转待办） | 单次输出有随机性，多路能看出哪些结论是稳的 |
| 一致度聚类 | 日期重叠且说法相近的合成一条，记下几路抽到（`4/4`、`3/4`） | 一致度写进事项文件，哪条该人工扫一眼一目了然 |
| 近义取全 | 同簇里「报名」与「汇总报名表」并存时取信息最全的那条 | 小模型常给缩写版，取缩写会丢宾语 |
| 变体留痕 | 同簇说法不一致时把其它说法写进 `variants`，文件里出现 ⚠️ | 分歧不该被静默票决掉 |
| 确定性核对 | 不花模型调用：① 模型抄的**依据**在材料里找得到吗 ② 这个**日期**在材料里有字面吗 | 直接打标，把「可疑」和「可信」分开 |
| 代码护栏 | 材料一个时间词都没有 → 模型给的日期整批丢掉；日期还要过合法性/范围/跨度检查 | 防「没日期就编一个」 |

**复核那一路默认是关的**，这是实测结论：让 4B 模型拿初稿当裁判，它把四条合成三条
（丢条目）、把「汇总报名表」缩写成「报名」。**小模型当裁判比当选手更差。**
想开：`config.toml` 里 `verify = true`。

想快：`inbox run --fast`（只问一次模型）。

## SSH 网关（手机触发归档的关键）

手机发完文件要「叫服务器干活」，但手机那把 key 原本只允许 SFTP。
`bin/transfer-gateway` 是这条 key 的 forced command，它**只**放行两件事：

```
sftp / internal-sftp / /usr/lib/openssh/sftp-server  →  exec sftp-server -d ~/phone-upload
archive-trigger（必须完全相等）                       →  exec bin/inbox trigger
其它任何输入                                          →  拒绝并记日志
```

它**从不 eval** `$SSH_ORIGINAL_COMMAND`：只对字面量做 `case` 匹配，执行写死的程序，
所以不存在命令注入。每次调用记一行到 `run/gateway.log`。

`authorized_keys` 里的写法（绝对路径，改路径要同步改这一行）：

```
command="/path/to/inbox-archiver/bin/transfer-gateway",no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA… inbox
```

## 配置

改 `config.toml`（改完直接生效）。环境变量优先级更高：
`ARCHIVE_ROOT`、`INBOX_DIR`、`RECORDS_DIR`、`TODOS_DIR`、`INBOX_STATE_DIR`、
`INBOX_OCR_MODE`、`INBOX_AUTOSTART`、`INBOX_CONFIG`。

主要项：

| 配置 | 默认 | 说明 |
|---|---|---|
| `paths.root/raw/records/todos` | `~/phone-archive{,/raw,/records,/todos}` | 三个区的位置 |
| `filters.min_stable_seconds` | 30 | mtime 距今小于它 → 认为还在传，本轮跳过 |
| `models.ocr_mode` | `always` | `always` / `auto`（只挑截图文档）/ `off` |
| `models.autostart` | true | 模型没起就自动拉起 |
| `models.llm_remote_chain` | 空 | 填了就走远程 OpenAI 兼容端点，按顺序降级 |
| `behavior.foreign_action` | `error` | 非文本/图片：`error` / `move` / `ignore` |
| `behavior.on_model_error` | `defer` | 模型拉不起来：留在 raw 等下次 / 照样归档 |
| `todos.passes` | 3 | 主提示词跑几路 |
| `todos.alt_prompt` | true | 是否加一路换切分 |
| `todos.verify` | **false** | 模型复核（实测会丢条目） |
| `todos.merge_threshold` | 0.6 | 两路结果算同一件事的门槛 |

## 出错时的行为

| 情况 | 行为 |
|---|---|
| 文件还在传（mtime 很新） | 跳过，留在 `raw/` |
| 模型服务起不来 | 默认不动文件，记 `deferred`，下次重跑 |
| 单张图 OCR 失败 | 照样打包，`text.md` 写「未识别出文字」并记 error |
| 抽取 0 条待办 | 正常（材料里确实没有带日期的事） |
| 触发时服务器正在跑 | 第二次触发直接跳过（`run.lock`），不会并发 |
| 归档中途失败 | 逐文件独立、可重跑，已完成的照旧 |

## 工程结构

```
inbox-archiver/
  bin/inbox                 入口
  bin/transfer-gateway      手机 SSH key 的受限 forced command
  config.toml               配置
  archiver/
    config.py               配置加载（toml + 环境变量）
    util.py                 类型判定、日期解析、文件名清洗、EXIF
    vision.py               调 OCR 服务
    todos.py                待办抽取提示词 + json_schema 约束解码 + 日期归一/护栏
    services.py             模型服务探活/拉起/GPU 探测
    store.py                sqlite（items + todos，可重跑可回退）
    library.py              records 打包 + todos/年.月.日/ 生成
    pipeline.py             主流程
    cli.py                  命令行
    preheat.py              重负载前预热（把风扇带起来）
  tests/selftest.py         离线自检（206 项，不碰模型也不碰真实归档库）
  tests/e2e-config.toml     端到端演练配置
  run/                      状态库、日志、锁（可整个删掉重建）
```

```bash
python3 tests/selftest.py                                    # 纯离线，秒级
python3 bin/inbox --config tests/e2e-config.toml run         # 端到端演练（真调模型）
```

## 已知边界

- 视频/音频/PDF/APK **不做内容理解**，存档区也收不了（App 层拦）
- 一次几十张图会很慢（小模型 + CPU OCR），分次跑用 `inbox run --limit 5`
- 待办抽取是小模型的判断，**不保证不漏**；要紧的事还是自己扫一眼
- 日期换算依赖材料日期（EXIF/文件名/mtime），上传时丢了原始时间就可能不准
- 手工改名/移动 `records/` 后状态库不会跟着变；`todos/` 可以用 `--rebuild` 重建
