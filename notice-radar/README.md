# notice-radar —— 校园通知聚合：抓取 → 打分 → 日报 → 推送

把一堆分散在各院系/部门网站上的通知公告抓下来，抽出正文（含 PDF/图片 OCR），
用一个本地或远程的小模型给每条打上**类别、截止日期、重要度、相关度**，去重后生成
按天组织的 Markdown 日报，最后推到你想要的地方。

```
栏目列表页 ──抓取──> articles.sqlite ──正文抽取──> 分析（LLM） ──> 日报 Markdown ──> webhook
                          ▲                 │
                          └── PDF / 图片 OCR ┘
```

零第三方依赖：只用 Python 标准库（`urllib` / `sqlite3` / `re` / `html`）。
模型走 OpenAI 兼容接口，默认本机。

## 快速开始

```bash
cp sites.example.json sites.json     # 填自己的站点
python3 notice_radar.py crawl        # 只抓取入库
python3 notice_radar.py analyze      # 只分析未分析的文章
python3 notice_radar.py report --days 7 > daily.md
python3 notice_radar.py run          # 抓取 + 分析 + 出日报，一条龙
python3 notice_radar.py stats        # 库里有什么
python3 notice_radar.py list --days 3
```

推送：

```bash
WEBHOOK_URL=https://example.com/hook python3 notify.py --file daily.md
python3 notify.py --dry-run --file daily.md     # 先看 payload
```

## 站点配置

`sites.json` 里每个站点是一条：

```json
{
  "id": "example-notices",
  "name": "示例通知公告",
  "base": "https://www.example.edu",
  "columns": [
    {"name": "通知公告", "path": "/tzgg/list.htm"}
  ]
}
```

- `columns[].path` 是**栏目列表页**，不是首页
- 同一套 Webplus CMS 的站点可以共用解析器；换 CMS 时改 `parse_list()` / `extract_body()`
- 抓取尊重 `robots.txt`（`respect_robots`），默认 1 秒间隔、每栏目最多 2 页

## 正文抽取：为什么需要回退链

列表页只给标题和链接，正文才是模型判断的依据。实际站点上会遇到：

| 情况 | 处理 |
|---|---|
| 正常 HTML 正文 | 找最大文本块，去掉导航、页脚、字号选择器 |
| 正文是图片（扫描件/海报） | 下载图片 → OCR 服务（默认 8083） |
| 正文是 PDF 附件 | 下载 → 先试 `pdftotext`，没有文本层就 `pdftoppm` 光栅化再 OCR |
| 正文区域是空的 | 退到附件列表里最大的 PDF |

`backfill_pdf.py` 用来给历史文章补跑 PDF 正文（新抓的会自动处理）；
`pdf_rasterize.py` 是单独的光栅化工具，方便手工排查。

## 模型打分

每条文章问一次模型，输出严格 JSON：

```json
{"summary": "一句话摘要", "category": "讲座学术", "audience": "本科生",
 "deadline": "2026-11-08", "importance": 4, "fit": 82, "fit_reason": "本院活动"}
```

- `backend: local` → 本机 llama.cpp / 任意 OpenAI 兼容端点（`llm.local`）
- `backend: free` → 远程降级链（`llm.chain`），密钥只从环境变量读：
  `MODELSCOPE_API_KEY` / `SILICONFLOW_API_KEY` / `ZAI_API_KEY` / `OPENROUTER_API_KEY`
- 免费档会 429，客户端带指数退避；单模型日上限用完就换下一个
- **优先 `json_schema` 而不是 `json_object`**：带思考链的模型在 `json_object` 下会先写
  一大段 reasoning 把 `max_tokens` 烧光，`content` 返回空串。判定成功的标准是
  「content 能解析出 JSON」，reasoning 再长也不算

`fit` 是「对普通本科生的有用程度」，不是「这条通知好不好」。面向研究生、教师、
科研项目申报的条目会拿到低分，但仍然保留在库里。

## 日报

按分类分组、按重要度排序，每条给标题、日期、摘要、截止日期与原文链接。
生成的是普通 Markdown，直接 `cat`、进静态站、或者经 `notify.py` 推到 IM 都行。

## 工程结构

```
notice_radar.py     主程序：抓取 / 正文抽取 / OCR / 打分 / 日报（单文件）
llm_remote.py       远程模型的降级链、退避重试、JSON 约束
pdf_rasterize.py    PDF → PNG（pdftoppm 封装）
backfill_pdf.py     给历史文章补 PDF 正文
notify.py           通用 webhook 推送（企业微信/钉钉/自建网关）
sites.example.json  站点配置示例
data/               sqlite 与附件缓存（运行时生成，不入库）
reports/            日报输出（运行时生成）
```

## 已知边界

- **只抓公开页面**。不登录、不碰需要会话的系统；遇到 401/403 记一条失败就跳过
- 解析器是按 Webplus CMS 的列表结构写的，换站点可能需要改 `parse_list()`
- 模型打分的 `deadline` 可能抽错；日报里只是提示，不要当成权威日期
- OCR 很慢（CPU 上单页数秒），PDF 只取前 `max_pages` 页
- 没有增量更新：正文改动不会重新抓，只有新链接会入库
- 请遵守目标站点的 robots.txt 与访问频率；个人自用，不要拿去做全站镜像
