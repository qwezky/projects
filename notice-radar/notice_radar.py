#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校园通知聚合：抓取栏目列表 → 正文抽取 → 本地/远程模型打分 → 日报。

零第三方依赖：只用 Python 标准库（urllib / sqlite3 / re / json / html）。
本地模型走 OpenAI 兼容接口，默认 http://127.0.0.1:8080/v1（Qwen3-4B）。

用法：
    python3 notice_radar.py run                 # 抓取 + 分析 + 出日报（一条龙）
    python3 notice_radar.py crawl               # 只抓取入库
    python3 notice_radar.py analyze             # 只分析未分析的文章
    python3 notice_radar.py report --days 7     # 只出日报
    python3 notice_radar.py stats               # 看库里有什么
    python3 notice_radar.py list --days 3       # 终端直接看近期条目
"""

from __future__ import annotations

import argparse
import gzip
import html as html_mod
import io
import json
import os
import re
import shutil
import sqlite3
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
import zlib
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DB_PATH = ROOT / "data" / "notices.db"
REPORT_DIR = ROOT / "reports"
CONFIG_PATH = Path(os.environ.get("SITES_CONFIG", ROOT / "sites.json"))

# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------

_CJK = re.compile(r"[\u4e00-\u9fff]")
_DATE_RE = re.compile(r"(\d{4})\s*[.\-/年]\s*(\d{1,2})\s*[.\-/月]\s*(\d{1,2})")


def log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def now_iso() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


# systemd 用户单元的 PATH 里**没有** /usr/local/nodejs/bin 和 ~/.local/bin，
# 所以 `node`（PDF 提取）和 `uv`（PDF 栅格化）在定时任务里会静默找不到。
# 一律用绝对路径兜底，不依赖调用方的 PATH。
_EXTRA_BIN_DIRS = ["/usr/local/nodejs/bin", str(Path.home() / ".local/bin"),
                   "/usr/local/bin", "/usr/bin", "/bin"]


def find_bin(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    for d in _EXTRA_BIN_DIRS:
        cand = Path(d) / name
        if cand.exists() and os.access(cand, os.X_OK):
            return str(cand)
    return None


def strip_tags(s: str) -> str:
    s = re.sub(r"<(script|style)\b.*?</\1>", " ", s, flags=re.S | re.I)
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = re.sub(r"</(p|div|li|tr|h[1-6])>", "\n", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    s = html_mod.unescape(s)
    s = s.replace("\u3000", " ").replace("\xa0", " ")
    return s


def clean_text(s: str) -> str:
    s = strip_tags(s)
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    s = re.sub(r"\n\s*\n+", "\n", s)
    return s.strip()


def cjk_len(s: str) -> int:
    return len(_CJK.findall(s or ""))


def norm_date(text: str) -> str | None:
    m = _DATE_RE.search(text or "")
    if not m:
        return None
    y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
    if not (1990 <= y <= 2100 and 1 <= mo <= 12 and 1 <= d <= 31):
        return None
    return f"{y:04d}-{mo:02d}-{d:02d}"


# --------------------------------------------------------------------------
# HTTP 抓取（带压缩、重试、编码嗅探、限速）
# --------------------------------------------------------------------------

class _UAByHostHandler(urllib.request.BaseHandler):
    """按【最终】主机名设置 User-Agent。

    必须在重定向之后重新判定：东大 CMS 里很多通知页是 302 跳到
    mp.weixin.qq.com 的，而微信对非浏览器 UA 一律返回「环境异常」验证页。
    若在发起请求时就固定 UA，跳转过去后用的还是机器人 UA，正文就丢了。
    """

    def __init__(self, ua_by_host: dict, default_ua: str):
        self.ua_by_host = ua_by_host
        self.default_ua = default_ua

    def _set(self, req: urllib.request.Request):
        host = urllib.parse.urlsplit(req.full_url).netloc.lower()
        req.add_unredirected_header("User-Agent",
                                    self.ua_by_host.get(host, self.default_ua))
        return req

    http_request = _set
    https_request = _set


class Fetcher:
    def __init__(self, cfg: dict):
        self.timeout = cfg.get("timeout", 20)
        self.retries = cfg.get("retries", 3)
        self.delay = cfg.get("delay", 1.0)
        self.ua = cfg.get("user_agent", "NoticeRadar/1.0")
        self.browser_ua = cfg.get(
            "browser_ua",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        # 微信对非浏览器 UA 一律返回「环境异常」验证页，只在该域名上换 UA
        self.ua_by_host = {"mp.weixin.qq.com": self.browser_ua}
        self.respect_robots = cfg.get("respect_robots", True)
        self._last_hit: dict[str, float] = {}
        self._robots: dict[str, urllib.robotparser.RobotFileParser | None] = {}
        self._ctx = ssl.create_default_context()
        self._ctx_insecure = ssl.create_default_context()
        self._ctx_insecure.check_hostname = False
        self._ctx_insecure.verify_mode = ssl.CERT_NONE
        self._opener = urllib.request.build_opener(
            _UAByHostHandler(self.ua_by_host, self.ua))
        self._opener_insecure = urllib.request.build_opener(
            _UAByHostHandler(self.ua_by_host, self.ua),
            urllib.request.HTTPSHandler(context=self._ctx_insecure))

    # --- robots ---
    def _robots_for(self, url: str):
        parts = urllib.parse.urlsplit(url)
        host = f"{parts.scheme}://{parts.netloc}"
        if host in self._robots:
            return self._robots[host]
        rp = urllib.robotparser.RobotFileParser()
        rp.set_url(host + "/robots.txt")
        try:
            raw, _ = self._raw_get(host + "/robots.txt", use_robots=False)
            rp.parse(raw.decode("utf-8", "replace").splitlines())
        except Exception:
            rp = None  # 拿不到（404 / 超时）视为不限制
        self._robots[host] = rp
        return rp

    def allowed(self, url: str) -> bool:
        if not self.respect_robots:
            return True
        rp = self._robots_for(url)
        if rp is None:
            return True
        try:
            return rp.can_fetch(self.ua, url)
        except Exception:
            return True

    # --- 限速 ---
    def _throttle(self, url: str) -> None:
        host = urllib.parse.urlsplit(url).netloc
        last = self._last_hit.get(host)
        if last is not None:
            wait = self.delay - (time.time() - last)
            if wait > 0:
                time.sleep(wait)
        self._last_hit[host] = time.time()

    # --- 原始请求 ---
    def _raw_get(self, url: str, use_robots: bool = True):
        if use_robots and not self.allowed(url):
            raise PermissionError(f"robots.txt 不允许抓取: {url}")
        self._throttle(url)
        req = urllib.request.Request(url, headers={
            "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9",
            "Accept-Encoding": "gzip, deflate",
        })
        last_err: Exception | None = None
        for attempt in range(self.retries):
            try:
                with self._opener.open(req, timeout=self.timeout) as r:
                    data = r.read()
                    enc = (r.headers.get("Content-Encoding") or "").lower()
                    if "gzip" in enc:
                        data = gzip.GzipFile(fileobj=io.BytesIO(data)).read()
                    elif "deflate" in enc:
                        try:
                            data = zlib.decompress(data)
                        except zlib.error:
                            data = zlib.decompress(data, -zlib.MAX_WBITS)
                    return data, r.headers.get_content_charset()
            except ssl.SSLError as e:
                last_err = e
                try:
                    with self._opener_insecure.open(req, timeout=self.timeout) as r:
                        return r.read(), r.headers.get_content_charset()
                except Exception as e2:
                    last_err = e2
            except Exception as e:  # noqa: BLE001
                last_err = e
            if attempt < self.retries - 1:
                time.sleep(1.5 * (attempt + 1))
        raise RuntimeError(f"抓取失败 {url}: {last_err}")

    def get_text(self, url: str) -> str:
        data, charset = self._raw_get(url)
        for enc in filter(None, [charset, _sniff_charset(data), "utf-8", "gb18030"]):
            try:
                return data.decode(enc)
            except (UnicodeDecodeError, LookupError):
                continue
        return data.decode("utf-8", "replace")


def _sniff_charset(data: bytes) -> str | None:
    head = data[:2048].decode("ascii", "ignore")
    m = re.search(r'charset=["\']?\s*([\w-]+)', head, re.I)
    return m.group(1) if m else None


# --------------------------------------------------------------------------
# Webplus 列表页解析
# --------------------------------------------------------------------------

ART_URL_RE = re.compile(r'href=["\']([^"\']*?/c\d+a\d+[^"\']*)["\']', re.I)
_TITLE_CLASS = re.compile(
    r'<(?:div|span|h[1-6]|p)[^>]*class=["\'][^"\']*news_title[^"\']*["\'][^>]*>(.*?)</(?:div|span|h[1-6]|p)>',
    re.S | re.I)
_SUMMARY_CLASS = re.compile(
    r'<(?:div|span|p)[^>]*class=["\'][^"\']*(?:news_text|news_con|news_summary)[^"\']*["\'][^>]*>(.*?)</(?:div|span|p)>',
    re.S | re.I)


def _anchor_title(block: str, href: str) -> str | None:
    """优先取带 title 属性的 <a>（jwc / ee / cs 模板）。

    注意：必须用列表页里的原始 href 去匹配，不能用 _abs() 之后的绝对 URL——
    标签里写的是相对路径。
    """
    key = href.split("?")[0]
    for m in re.finditer(r"<a\b[^>]*>", block, re.I):
        tag = m.group(0)
        if key not in tag:
            continue
        t = re.search(r"""title=["']([^"']*)["']""", tag)
        if t and t.group(1).strip():
            return clean_text(t.group(1))
    return None


def _block_date(block: str) -> str | None:
    # 新闻网把日期拆成 news_day(14) + news_year(2026.10)
    d = re.search(r"""class=["'][^"']*news_day[^"']*["'][^>]*>\s*(\d{1,2})\b""", block)
    y = re.search(r"""class=["'][^"']*news_year[^"']*["'][^>]*>\s*(\d{4})\s*[.\-/年]\s*(\d{1,2})""",
                  block)
    if d and y:
        return f"{int(y.group(1)):04d}-{int(y.group(2)):02d}-{int(d.group(1)):02d}"
    return norm_date(block)


def parse_list(html: str, base: str) -> list[dict]:
    """从栏目列表页解析条目。对 li / tr 两种模板都适用。"""
    items: list[dict] = []
    seen: set[str] = set()

    # 1) 找"只含一个文章链接"的最小容器块（li 或 tr）
    blocks: list[str] = []
    for pat in (r"<li\b[^>]*>.*?</li>", r"<tr\b[^>]*>.*?</tr>"):
        for m in re.finditer(pat, html, re.S | re.I):
            blocks.append(m.group(0))

    for blk in blocks:
        hrefs = set(ART_URL_RE.findall(blk))
        if len(hrefs) != 1:
            continue
        href = hrefs.pop()
        url = _abs(base, href)
        if url in seen:
            continue
        title = _anchor_title(blk, href)
        if not title:
            m = _TITLE_CLASS.search(blk)
            if m:
                title = clean_text(m.group(1))
        if not title:
            continue
        if len(title) < 4 or title.isdigit():
            continue
        date = _block_date(blk)
        summ = ""
        m = _SUMMARY_CLASS.search(blk)
        if m:
            summ = clean_text(m.group(1))[:400]
        seen.add(url)
        items.append({"url": url, "title": title, "date": date, "summary": summ})

    # 2) 兜底：模板没有 li/tr 包裹时，逐个 <a> 标签处理。
    #    不能写成 <a ...>.*?</a>：部分站点存在未闭合/嵌套的 <a>，
    #    非贪婪匹配会一次吞掉好几行，导致条目丢失。
    if not items:
        for m in re.finditer(r"<a\b[^>]*>", html, re.I):
            tag = m.group(0)
            um = ART_URL_RE.search(tag)
            if not um:
                continue
            href = um.group(1)
            url = _abs(base, href)
            if url in seen:
                continue
            t = re.search(r"""title=["']([^"']*)["']""", tag)
            if t and t.group(1).strip():
                title = clean_text(t.group(1))
            else:
                seg = html[m.end():m.end() + 300].split("</a>")[0]
                title = clean_text(seg)
            if len(title) < 4 or title.isdigit():
                continue
            date = norm_date(html[max(0, m.start() - 250):m.end() + 250])
            seen.add(url)
            items.append({"url": url, "title": title, "date": date, "summary": ""})

    # 去重（同一 URL 保留信息最全的一条）
    uniq: dict[str, dict] = {}
    for it in items:
        old = uniq.get(it["url"])
        if old is None or (not old.get("date") and it.get("date")):
            uniq[it["url"]] = it
    return list(uniq.values())


def page_urls(col_url: str, max_pages: int) -> list[str]:
    """Webplus 分页：list.htm, list2.htm, list3.htm ..."""
    if max_pages <= 1:
        return [col_url]
    base, ext = os.path.splitext(col_url)
    out = [col_url]
    for i in range(2, max_pages + 1):
        out.append(f"{base}{i}{ext}")
    return out


def next_page_exists(html: str) -> bool:
    return bool(re.search(r"""href=["'][^"']*list\d+\.htm["']""", html))


def _abs(base: str, href: str) -> str:
    """把相对链接转成绝对链接。

    必须用 urljoin(base, href) 原样传 href：根路径（/_upload/...）会正确
    解析到站点根，而 href.lstrip('/') 会把它错误地拼到 base 的目录之后
    （例如 /2026/xxx/page.htm/_upload/...）。这里踩过坑。
    """
    if href.startswith("//"):
        return "https:" + href
    if href.startswith("http"):
        return href
    return urllib.parse.urljoin(base, href)


# --------------------------------------------------------------------------
# 正文抽取
# --------------------------------------------------------------------------

_OPEN_DIV = re.compile(r"<div\b", re.I)
_CLOSE_DIV = re.compile(r"</div>", re.I)

# (选择器, 该选择器被接受所需的最少中文字数)
# 按优先级从高到低排列：高优先级选择器一旦命中足够的正文就立即采用，
# 不允许"字数更多的导航块"把它挤掉（这是 early 版本踩过的坑）。
_BODY_PATTERNS: list[tuple[str, int]] = [
    (r'<div[^>]*class=["\'][^"\']*wp_articlecontent[^"\']*["\'][^>]*>', 30),
    (r'<div[^>]*id=["\']js_content["\'][^>]*>', 80),
    (r'<div[^>]*class=["\'][^"\']*rich_media_content[^"\']*["\'][^>]*>', 80),
    (r'<div[^>]*id=["\'](?:content|article|zoom|vsb_content|mainContent)["\'][^>]*>', 80),
    (r'<div[^>]*class=["\'][^"\']*(?:articleContent|article[_-]?content|'
     r'content[_-]?body|entry-content|v_news_content|TRS_Editor)[^"\']*["\'][^>]*>', 60),
    (r'<div[^>]*frag=["\'][^"\']*["\'][^>]*>', 120),
]

_STRIP_BLOCKS = re.compile(
    r"<(script|style|nav|header|footer|form|select)\b.*?</\1>", re.S | re.I)

_ANCHOR_INNER = re.compile(r"<a\b[^>]*>(.*?)</a>", re.S | re.I)


def _link_ratio(chunk: str) -> float:
    """块内中文有多少比例落在 <a> 里。导航/列表块接近 1，正文块接近 0。"""
    total = cjk_len(clean_text(chunk))
    if total < 20:
        return 0.0
    linked = sum(cjk_len(clean_text(m.group(1))) for m in _ANCHOR_INNER.finditer(chunk))
    return linked / total


def _balanced_div(html: str, pos: int) -> str:
    """从 pos 处的 <div 开始，返回配平的 div 内容。"""
    depth = 0
    i = pos
    while i < len(html):
        o = _OPEN_DIV.search(html, i)
        c = _CLOSE_DIV.search(html, i)
        if c is None:
            break
        if o is not None and o.start() < c.start():
            depth += 1
            i = o.end()
        else:
            depth -= 1
            i = c.end()
            if depth == 0:
                return html[pos:i]
    return html[pos:]


def extract_body(html: str) -> str:
    """多策略正文抽取：按选择器优先级取第一个"够长且不是导航"的块。"""
    doc = _STRIP_BLOCKS.sub(" ", html)
    for pat, minchars in _BODY_PATTERNS:
        best, best_score = "", 0
        for m in re.finditer(pat, doc, re.I):
            chunk = _balanced_div(doc, m.start())
            if _link_ratio(chunk) > 0.6:
                continue  # 导航/链接列表，不是正文
            t = clean_text(chunk)
            s = cjk_len(t)
            if s > best_score:
                best, best_score = t, s
        if best_score >= minchars:
            return _tidy_body(best)
    return _tidy_body(_largest_text_block(doc))


# 微信验证页/参数错误页的特征串——抓到这些说明正文没拿到，别喂给模型
BAD_BODY_MARKERS = ("环境异常", "完成验证后即可继续访问", "请在微信客户端打开链接",
                    "参数错误", "该内容已被发布者删除", "此内容因违规无法查看")


def body_is_usable(body: str) -> bool:
    if cjk_len(body) < 40:
        return False
    head = body[:200]
    return not any(k in head for k in BAD_BODY_MARKERS)


def text_usable(text: str) -> bool:
    """PDF/OCR 文本够不够当正文。

    不能用 cjk_len —— 英文海报/双语文档会被误杀（实测一份英文讲座海报
    3111 字符只含 33 个汉字）。改用「非空白字符数」，再加一条重复检测：
    VLM 在极端长宽比的图上会退化成同一行刷屏（实测重复 901 次）。
    """
    body = re.sub(r"\s", "", text or "")
    if len(body) < 40:
        return False
    lines = [ln.strip() for ln in (text or "").split("\n") if ln.strip()]
    if len(lines) >= 8:
        from collections import Counter
        top = Counter(lines).most_common(1)[0][1]
        if top > 20 and top / len(lines) > 0.5:
            return False                      # 一行刷屏 = OCR 退化了
    return True


# --------------------------------------------------------------------------
# PDF 附件正文
# --------------------------------------------------------------------------
# 很多通知的「正文」其实是一个 PDF（培养方案、值班表、评审办法…）。Webplus 用
# wp_pdf_player 承载，HTML 里没有文字，只能把 PDF 下下来提。
# 优先用 poppler 的 `pdftotext`（装了就有，没有就跳过），文字层为空再走 OCR。

PDF_CACHE = ROOT / "data" / "attachments"
PDF_MAX_BYTES = 25 * 1024 * 1024
PDF_TIMEOUT = 180


def _pdf_text(pdf: Path, timeout: int) -> str:
    """pdftotext 抽文字层；没装 poppler 或抽不出来就返回空串。"""
    exe = find_bin("pdftotext")
    if not exe:
        return ""
    try:
        r = subprocess.run(  # noqa: S603
            [exe, "-layout", "-enc", "UTF-8", str(pdf), "-"],
            capture_output=True, timeout=timeout)
        if r.returncode == 0:
            return r.stdout.decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        pass
    return ""


def ocr_image(png: Path, cfg: dict) -> tuple[str, str]:
    """把一张图送去 PaddleOCR-VL（OpenAI 兼容）。返回 (文本, 错误)。"""
    import base64
    host = cfg.get("host", "127.0.0.1")
    port = cfg.get("port", 8083)
    url = f"http://{host}:{port}/v1/chat/completions"
    payload = {
        "model": "paddleocr-vl",
        "temperature": 0,
        "max_tokens": 8192,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": cfg.get("prompt", "OCR:")},
            {"type": "image_url", "image_url": {
                "url": "data:image/png;base64," + base64.b64encode(
                    png.read_bytes()).decode()}},
        ]}],
    }
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(
                req, timeout=cfg.get("timeout", 600)) as r:
            resp = json.load(r)
    except Exception as exc:  # noqa: BLE001
        return "", f"{type(exc).__name__}: {str(exc)[:80]}"
    txt = ((resp.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    return txt.strip(), ""


VISION_PROVIDERS = {
    "openrouter": ("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
    "modelscope": ("https://api-inference.modelscope.cn/v1", "MODELSCOPE_API_KEY"),
}


def vision_ocr(png: Path, cfg: dict) -> tuple[str, str]:
    """用视觉大模型识别整页文字。cfg: {chain: ["provider:model", ...], ...}

    实测（2026-10-18）：OpenRouter 的两个免费视觉模型可用，但**免费档
    每日 50 次**、且与文本分析共用同一份额度 —— 配额用尽会 429
    `free-models-per-day`。所以失败时由调用方回落到本地 OCR。
    """
    from llm_remote import read_ref

    import base64
    chain = cfg.get("chain") or []
    prompt = cfg.get("prompt", "请把这张图片里的文字完整识别出来，"
                               "保持原有顺序与表格结构，不要添加任何解释。")
    data_url = "data:image/png;base64," + base64.b64encode(
        png.read_bytes()).decode()
    last = ""
    for item in chain:
        provider, _, model = str(item).partition(":")
        provider, model = provider.strip(), model.strip()
        if provider not in VISION_PROVIDERS or not model:
            continue
        base, ref = VISION_PROVIDERS[provider]
        try:
            key = read_ref(ref)
        except Exception as exc:  # noqa: BLE001
            last = f"{provider}: 取不到凭据 {exc}"
            continue
        payload = {
            "model": model,
            "temperature": 0,
            "max_tokens": cfg.get("max_tokens", 3000),
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": data_url}},
            ]}],
        }
        req = urllib.request.Request(
            base + "/chat/completions", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {key}",
                     "HTTP-Referer": "http://localhost",
                     "X-Title": "notice-radar"})
        try:
            with urllib.request.urlopen(
                    req, timeout=cfg.get("timeout", 180)) as r:
                resp = json.load(r)
        except Exception as exc:  # noqa: BLE001
            last = f"{provider}/{model}: {type(exc).__name__} {str(exc)[:80]}"
            continue
        txt = ((resp.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        if txt.strip():
            return txt.strip(), ""
        last = f"{provider}/{model}: 返回空"
    return "", last or "没有配置可用的视觉模型"


def ocr_page(png: Path, cfg: dict) -> tuple[str, str]:
    """识别一页图。后端顺序由 cfg["order"] 决定。

    用户 2026-10-18 的要求：**本地 PaddleOCR-VL 优先，视觉大模型最后用**。
    理由：本地无配额限制、中文长文档更稳，而 OpenRouter 免费视觉只有
    50 次/天且与文本分析共用；ModelScope 视觉扣魔粒。
    """
    order = cfg.get("order") or ["local", "vision"]
    errs = []
    for backend in order:
        if backend == "local":
            loc = cfg.get("local") or {}
            if not loc.get("host"):
                continue
            if not ocr_service_up(loc):
                errs.append("本地 OCR 服务未启动")
                continue
            return ocr_image(png, loc)
        if backend == "vision":
            txt, err = vision_ocr(png, cfg.get("vision") or {})
            if txt:
                return txt, ""
            if err:
                errs.append(f"视觉:{err[:60]}")
    return "", "; ".join(errs) or "没有可用的 OCR 后端"


def _status_note(status: str, body: str, html: str, base: str) -> str | None:
    """给「正文抓不到」的条目留一句人话，让日报读者知道点进去会遇到什么。"""
    if status == "ok" or status in ("ok_pdf", "ok_img"):
        return None
    if status == "attachment":
        return "正文是一个附件，文字已尽可能提取，剩下的请点附件看原件"
    # no_body：区分「正文是图片」「页面本来就没内容」
    try:
        imgs = html_content_images(html, base, limit=1)
    except Exception:  # noqa: BLE001
        imgs = []
    if imgs:
        return ("正文是一张图片（不是文字），OCR 没识别出可用文字 —— "
                "点下方「正文图片」看原图")
    return "这个页面里没有正文文字（可能内容在别处，或页面本身是空的）"


def html_content_images(html: str, base: str, limit: int = 3) -> list[str]:
    """从文章页里挑「正文图」的 URL。

    东大 Webplus 的正文图在 `/_upload/article/images/`，站点装饰图在
    `/_upload/tpl/`，还有 `/_visitcount` 这种统计像素 —— 后两类必须排除。
    优先取落在正文容器（wp_articlecontent / frag 面板）里的图。
    """
    out: list[str] = []
    seen: set[str] = set()
    img_src = re.compile(r"""<img[^>]*src=["']([^"']+)["']""", re.I)

    def add(src: str) -> None:
        """加一张图；`_s`(缩略) 自动换成 `_d`(大图) 并排在前面。

        东大同一张图有 `_s` 与 `_d` 两档，列表/正文里往往嵌的是 `_s`。
        直接 OCR 缩略图只能认出几个字（实测 68KB 的 _s 只出 8 字）。
        """
        if not src or src.startswith("data:"):
            return
        if re.search(r"/_upload/(tpl|system)|/_visitcount|/favicon", src, re.I):
            return
        variants = []
        big = re.sub(r"_s(\.[a-zA-Z]+)$", r"_d\1", src)
        if big != src:
            variants.append(big)
        variants.append(src)
        for v in variants:
            url = _abs(base, v)
            if url in seen:
                continue
            seen.add(url)
            out.append(url)

    # ① 正文容器内的图最可靠
    for pat in (r"""<div[^>]*class=["'][^"']*wp_articlecontent[^"']*["'][^>]*>""",
                r"""<div[^>]*frag=["'][^"']*["'][^>]*>"""):
        for m in re.finditer(pat, html, re.I):
            chunk = _balanced_div(html, m.start())
            if _link_ratio(chunk) > 0.6:
                continue
            for src in img_src.findall(chunk):
                add(src)
            if out:
                return out[:limit]
    # ② 兜底：所有 /_upload/article/images/ 下的图。
    #    东大同一张图有 `_s`(缩略) 与 `_d`(大图) 两档，列表页给的是 _s，
    #    直接 OCR 缩略图只能认出几个字（实测 68KB 的 _s 只出 8 字）。
    #    所以把 `_s.xxx` 换成 `_d.xxx` 放前面。
    for src in img_src.findall(html):
        if "/_upload/article/images/" in src:
            add(src)
    return out[:limit]


def image_to_text(fetcher, url: str, ocr_cfg: dict) -> str:
    """下载一张图并 OCR。"""
    name = re.sub(r"[^0-9A-Za-z._\u4e00-\u9fff-]", "_",
                  urllib.parse.unquote(url.rsplit("/", 1)[-1]))[:120] or "img"
    if not re.search(r"\.(png|jpe?g|webp|gif|bmp)$", name, re.I):
        name += ".png"
    PDF_CACHE.mkdir(parents=True, exist_ok=True)
    dst = PDF_CACHE / name
    if not dst.exists():
        try:
            data, _ = fetcher._raw_get(url)  # noqa: SLF001
        except Exception:  # noqa: BLE001
            return ""
        # 太小基本是图标/装饰
        if not data or len(data) < 5120:
            return ""
        dst.write_bytes(data)
        # 大图先缩到长边 1600px：原图 27 MB 时 base64 有 36 MB，
        # 直接 POST 给 llama-server 太重；OCR 本身也会降采样
        if len(data) > 3 * 1024 * 1024:
            try:
                from PIL import Image
                im = Image.open(dst)
                if max(im.size) > 1600:
                    k = 1600 / max(im.size)
                    im = im.resize((int(im.size[0] * k), int(im.size[1] * k)),
                                   Image.LANCZOS)
                im.convert("RGB").save(dst, "PNG")
                log(f"  · 大图已缩到 {im.size[0]}x{im.size[1]} 再 OCR")
            except Exception as exc:  # noqa: BLE001
                log(f"  · 缩放失败，按原图试：{type(exc).__name__}")
    txt, err = ocr_page(dst, ocr_cfg)
    if err and not txt:
        log(f"  · 图片 OCR 失败（{name[:28]}）: {err[:60]}")
    return txt


def ocr_service_up(cfg: dict) -> bool:
    host = cfg.get("host", "127.0.0.1")
    port = cfg.get("port", 8083)
    try:
        with urllib.request.urlopen(
                f"http://{host}:{port}/health", timeout=3) as r:
            return r.status == 200
    except Exception:  # noqa: BLE001
        return False


def rasterize_pdf(pdf: Path, outdir: Path, dpi: int, max_pages: int) -> list[Path]:
    """用 uv + PyMuPDF 把 PDF 前几页转成 PNG（本机没有任何栅格化工具）。"""
    uv = find_bin("uv")
    script = Path(__file__).with_name("pdf_rasterize.py")
    if not uv or not script.exists():
        return []
    try:
        r = subprocess.run(  # noqa: S603
            [uv, "run", "--with", "pymupdf", "python", str(script),
             str(pdf), str(outdir), "--dpi", str(dpi),
             "--max-pages", str(max_pages)],
            capture_output=True, timeout=300)
    except Exception:  # noqa: BLE001
        return []
    if r.returncode != 0:
        return []
    return sorted(outdir.glob("page-*.png"))


def pdf_ocr(pdf: Path, cfg: dict, cache: Path) -> str:
    """扫描件 PDF：栅格化前 N 页 → 逐页 OCR → 拼起来。"""
    if not cfg.get("enabled"):
        return ""
    loc = cfg.get("local") or {}
    has_vision = bool((cfg.get("vision") or {}).get("chain"))
    has_local = bool(loc.get("host")) and ocr_service_up(loc)
    if not has_vision and not has_local:
        log("  · 没有可用的 OCR 后端（本地服务未启动 / 视觉模型未配），跳过")
        return ""
    outdir = cache / (pdf.stem + ".pages")
    pngs = sorted(outdir.glob("page-*.png"))
    if not pngs:
        pngs = rasterize_pdf(pdf, outdir, cfg.get("dpi", 200),
                             cfg.get("max_pages", 2))
    if not pngs:
        return ""
    parts = []
    for i, png in enumerate(pngs, 1):
        txt, err = ocr_page(png, cfg)
        if err:
            log(f"  · OCR 第 {i} 页失败: {err}")
            continue
        if txt.strip():
            parts.append(txt.strip())
    return "\n\n".join(parts)


def pdf_to_text(fetcher, url: str, timeout: int = PDF_TIMEOUT,
                ocr_cfg: dict | None = None) -> str:
    """下载 PDF 并提成纯文本。任何失败都返回空串（不影响主流程）。

    先试 PDF 的文字层（pdfjs，快）；提不出字（扫描件）再走
    栅格化 + PaddleOCR-VL。OCR 结果会和文字层结果一起缓存成 .txt。
    """

    name = urllib.parse.unquote(url.rsplit("/", 1)[-1] or "")
    if not name.lower().endswith(".pdf"):
        return ""
    # 文件名可能带路径分隔符/奇怪字符，收成安全名
    name = re.sub(r"[^0-9A-Za-z._\u4e00-\u9fff-]", "_", name)[:120]
    PDF_CACHE.mkdir(parents=True, exist_ok=True)
    pdf = PDF_CACHE / name
    txt = PDF_CACHE / (name + ".txt")
    if txt.exists():                      # 已经提过，直接复用
        return txt.read_text(encoding="utf-8", errors="replace")
    if not pdf.exists():
        try:
            data, _ = fetcher._raw_get(url)  # noqa: SLF001
        except Exception:  # noqa: BLE001
            return ""
        if not data or len(data) > PDF_MAX_BYTES:
            return ""
        pdf.write_bytes(data)
    text = _pdf_text(pdf, timeout)
    if text:
        txt.write_text(text, encoding="utf-8")

    # 文字层提不出字 → 扫描件，走 OCR
    if not text_usable(text) and ocr_cfg:
        got = pdf_ocr(pdf, ocr_cfg, PDF_CACHE)
        if text_usable(got):
            text = got
            txt.write_text(text, encoding="utf-8")
            log(f"  · OCR 补出正文 {cjk_len(text)} 字（{name[:40]}）")
    return text


# --------------------------------------------------------------------------
# 附件抽取
# --------------------------------------------------------------------------
# 很多通知的"正文"其实是一个 PDF/Word 附件（Webplus 用 wp_pdf_player 承载）。
# 这类页面正文天然为空；至少要把附件链接留下来，否则条目就是死路。

_ATTACH_EXT = r"(?:pdf|docx?|xlsx?|pptx?|zip|rar)"

_ATTACH_RE = re.compile(
    r"""(?:href|pdfsrc|src)=["']([^"']*?/_upload/[^"']*?\.""" + _ATTACH_EXT + r""")["']""",
    re.I)

# 整个标签：URL 和中文标题写在同一个 sudyfile-attr 标签里，
# 必须按标签配对，不能用"附近窗口"——否则一篇有多个附件时标题会串位。
_ATTR_TAG_RE = re.compile(r"<[a-zA-Z][^>]*sudyfile-attr\s*=[^>]*>", re.I)
_ATTACH_IN_TAG_RE = re.compile(
    r"""(?:href|pdfsrc|src)=["']([^"']*?/_upload/[^"']*?\.""" + _ATTACH_EXT + r""")["']""",
    re.I)

_SUDY_TITLE_RE = re.compile(
    r"""sudyfile-attr=["']\{[^}]*?['"]title['"]\s*:\s*['"]([^'"]*)['"]""", re.I)


def extract_attachments(html: str, page_url: str) -> list[dict]:
    out: list[dict] = []
    seen: set[str] = set()

    def add(href: str, name: str) -> None:
        url = _abs(page_url, href)
        # 按 URL 去重：同一个文件可能既出现在 sudyfile-attr 里（有好标题），
        # 又作为普通 <a> 出现（只有文件名）。先到先得，所以 sudyfile-attr
        # 那一轮先跑，好标题不会被文件名覆盖。
        key = url.split("?")[0].lower()
        if key in seen:
            return
        seen.add(key)
        if not name:
            name = urllib.parse.unquote(url.rsplit("/", 1)[-1])
        out.append({"url": url, "name": name[:120]})

    # 1) 首选：sudyfile-attr 标签内 URL 与中文标题成对出现
    for m in _ATTR_TAG_RE.finditer(html):
        tag = m.group(0)
        u = _ATTACH_IN_TAG_RE.search(tag)
        if not u:
            continue
        t = _SUDY_TITLE_RE.search(tag)
        add(u.group(1), html_mod.unescape(t.group(1)).strip() if t else "")

    # 2) 兜底：页面上其它 /_upload/... 附件链接（标题取 <a> 文字或文件名）
    for m in _ATTACH_RE.finditer(html):
        href = m.group(1)
        a = re.search(r"""<a[^>]*href=["']""" + re.escape(href) +
                      r"""["'][^>]*>(.*?)</a>""", html, re.S | re.I)
        add(href, clean_text(a.group(1)) if a else "")

    return out[:12]


def _largest_text_block(doc: str) -> str:
    """兜底：按行切分，找连续中文段落最长的一段。"""
    text = clean_text(doc)
    lines = [l.strip() for l in text.split("\n")]
    lines = [l for l in lines if cjk_len(l) > 0]
    return "\n".join(lines)


_BOILER_LINE = re.compile(
    r"^(首页|上一页|下一页|尾页|返回|打印|关闭|分享|字体|大|中|小|更多|更多\+|版权所有|"
    r"Copyright|苏ICP|友情链接|相关链接|上一条|下一条|附件[:：]?|扫一扫|微信|微博|"
    r"简体中文|English|\|)$", re.I)

# 东大各站导航栏的固定标签。只在“短行”上匹配，避免误伤正文里的词。
_NAV_LABELS = {
    "院长书记信箱", "学院概况", "学院简介", "历史沿革", "组织机构", "联系我们", "联系方式",
    "师资队伍", "知名专家", "研究生导师", "教师按职称", "教师按研究方向", "教师按系别",
    "院机关与实验人员", "计算中心", "现任领导", "现任教师", "兼职教授", "客座教授",
    "退休教师", "历史人物", "人才培养", "本科生", "研究生", "专业简介", "培养方案",
    "教学管理", "教学动态", "通知公告", "下载专区", "博士招生", "硕士招生", "培养管理",
    "文件规定", "拔尖基地", "基地简介", "招生选拔", "基地网站", "学生工作", "学工概况",
    "学生活动", "就业信息", "媒体聚焦", "科学研究", "研究方向", "科研平台", "科研动态",
    "学术活动", "科研指南", "人才引进", "人才招聘", "博士后招募", "党群工作", "学习专区",
    "党建工作", "关工委工作", "工会工作", "校友天地", "校友风采", "校友动态",
    "部门简介", "领导分工", "处办公室", "招生办公室", "教学研究科", "质量评估科",
    "教务科", "学籍管理科", "实践教学科", "丁家桥教务办", "文化素质教育中心",
    "教学服务中心", "办事平台", "信息通知", "最新动态", "教务信息", "课程大纲",
    "培养方案", "科研机构", "教育教学", "招生就业", "学校简介", "历任领导",
    "两院院士风采", "公共服务", "在校生", "教职工", "访客", "查看更多", "更多", "导航",
}

# 元信息行（发布日期另有字段记录，这些行是噪声）
_META_LINE = re.compile(r"^(发布者|发布人|编辑|来源|供稿|摄影|浏览次数|点击次数|"
                        r"字体|分享到|微信扫一扫)\s*[:：]", re.I)

# 页脚起点：从这里往后的都是地址/版权等模板内容
_FOOTER_RE = re.compile(r"^(通信地址|地\s*址|邮\s*编|版权所有|友情链接|苏ICP|Copyright|"
                        r"电\s*话[:：]|传\s*真[:：]|E-?mail\s*[:：])", re.I)


def _cut_footer(lines: list[str]) -> list[str]:
    """从第一个页脚标记处截断，但仅当前面已有足够正文时。"""
    for i, l in enumerate(lines):
        if _FOOTER_RE.match(l.strip()):
            if sum(cjk_len(x) for x in lines[:i]) >= 40:
                return lines[:i]
    return lines


def _is_nav_line(line: str) -> bool:
    s = line.strip()
    if not s:
        return True
    if _BOILER_LINE.match(s) or _META_LINE.match(s):
        return True
    return cjk_len(s) <= 14 and s in _NAV_LABELS


def _tidy_body(text: str) -> str:
    lines = [l.strip() for l in text.split("\n") if l.strip()]
    lines = _cut_footer(lines)

    # 去掉导航行与元信息行
    out = [l for l in lines if not _is_nav_line(l)]

    # 再扫一遍：连续 >=4 行的超短行（<=12 汉字）基本就是菜单/列表，删掉
    cleaned: list[str] = []
    i = 0
    while i < len(out):
        if cjk_len(out[i]) <= 12:
            j = i
            while j < len(out) and cjk_len(out[j]) <= 12:
                j += 1
            if j - i >= 4:
                i = j
                continue
        cleaned.append(out[i])
        i += 1

    body = "\n".join(cleaned)
    body = re.sub(r"\n{3,}", "\n\n", body)
    return body.strip()


def article_date(html: str, fallback: str | None) -> str | None:
    m = re.search(r"(?:发布时间|发布日期|时间|日期)\s*[:：]?\s*"
                  r"(\d{4})\s*[.\-/年]\s*(\d{1,2})\s*[.\-/月]\s*(\d{1,2})", html)
    if m:
        return f"{int(m.group(1)):04d}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"
    m = re.search(r'name=["\'](?:PubDate|pubdate|publishdate)["\'][^>]*'
                  r'content=["\'](\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})', html, re.I)
    if m:
        return f"{int(m.group(1)):04d}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"
    return fallback


# --------------------------------------------------------------------------
# 数据库
# --------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS articles (
    url         TEXT PRIMARY KEY,
    site_id     TEXT NOT NULL,
    site_name   TEXT,
    column_name TEXT,
    title       TEXT NOT NULL,
    published   TEXT,
    list_summary TEXT,
    body        TEXT,
    body_len    INTEGER DEFAULT 0,
    fetched_at  TEXT,
    http_status TEXT DEFAULT 'ok',
    attachments TEXT DEFAULT '[]',
    note        TEXT
);
CREATE INDEX IF NOT EXISTS idx_articles_published ON articles(published);
CREATE INDEX IF NOT EXISTS idx_articles_site ON articles(site_id);

CREATE TABLE IF NOT EXISTS analysis (
    url        TEXT PRIMARY KEY,
    summary    TEXT,
    category   TEXT,
    audience   TEXT,
    deadline   TEXT,
    importance INTEGER,
    fit        INTEGER,
    fit_reason TEXT,
    model      TEXT,
    analyzed_at TEXT,
    error      TEXT
);

CREATE TABLE IF NOT EXISTS run_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT, finished_at TEXT,
    new_articles INTEGER, analyzed INTEGER, note TEXT
);
"""


def db_connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    _migrate(conn)
    return conn


def _migrate(conn: sqlite3.Connection) -> None:
    """给早先建好的库补上新列，避免用户必须删库重来。"""
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(articles)")}
    if "attachments" not in cols:
        conn.execute("ALTER TABLE articles ADD COLUMN attachments TEXT DEFAULT '[]'")
    if "note" not in cols:
        conn.execute("ALTER TABLE articles ADD COLUMN note TEXT")
    acols = {r["name"] for r in conn.execute("PRAGMA table_info(analysis)")}
    if "fit" not in acols:
        conn.execute("ALTER TABLE analysis ADD COLUMN fit INTEGER")
    if "fit_reason" not in acols:
        conn.execute("ALTER TABLE analysis ADD COLUMN fit_reason TEXT")
    conn.commit()


# --------------------------------------------------------------------------
# 抓取流程
# --------------------------------------------------------------------------

def crawl(config: dict, only_site: str | None = None, refresh: bool = False,
          quiet: bool = False, no_pdf: bool = False) -> int:
    cfg = config["crawl"]
    fetcher = Fetcher(cfg)
    conn = db_connect()
    cur = conn.cursor()
    known = {r["url"] for r in cur.execute("SELECT url FROM articles")}
    new_count = 0
    limit = cfg.get("limit_per_column", 40)

    sites = [s for s in config["sites"] if not only_site or s["id"] == only_site]
    if not sites:
        log(f"没有匹配的站点: {only_site}")
        return 0

    for site in sites:
        for col in site.get("columns") or []:
            col_url = _abs(site["base"], col["path"])
            found: list[dict] = []
            for pu in page_urls(col_url, cfg.get("max_pages", 2)):
                try:
                    html = fetcher.get_text(pu)
                except Exception as e:  # noqa: BLE001
                    log(f"  ! {site['name']}/{col['name']} 列表页失败: {e}")
                    break
                got = parse_list(html, site["base"])
                if not got:
                    break
                found.extend(got)
                if len(found) >= limit or not next_page_exists(html):
                    break
            found = found[:limit]

            # 只抓新的（除非 --refresh）
            todo = [it for it in found if refresh or it["url"] not in known]
            if not quiet:
                log(f"{site['name']} / {col['name']}: 列表 {len(found)} 条，待抓 {len(todo)} 条")

            for it in todo:
                try:
                    ahtml = fetcher.get_text(it["url"])
                    body = extract_body(ahtml)
                    pub = article_date(ahtml, it["date"])
                    atts = extract_attachments(ahtml, it["url"])
                    status = "ok"
                    if not body_is_usable(body):
                        # 抓到的多半是验证页/错误页，正文置空，只留列表页信息
                        status = "attachment" if atts else "no_body"
                        if not quiet:
                            extra = (f"，有 {len(atts)} 个附件" if atts else "")
                            log(f"  · 正文不可用（{status}{extra}）: {it['title'][:40]}")
                        body = ""
                    # 正文是 PDF 附件的，试着把 PDF 里的文字提出来当正文
                    if status in ("attachment", "no_body") and atts and not no_pdf:
                        for a in atts:
                            if not a["url"].lower().endswith(".pdf"):
                                continue
                            got = pdf_to_text(fetcher, a["url"],
                                              ocr_cfg=config.get("ocr"))
                            if cjk_len(got) >= 40:
                                body = got[:8000]
                                status = "ok_pdf"
                                if not quiet:
                                    log(f"  ✓ 从 PDF 提到正文 {cjk_len(body)} 字: "
                                        f"{a['name'][:34]}")
                            break          # 只看第一个 PDF，避免一篇下十几份
                    # 正文既不是文字也不是 PDF —— 常见于「通知正文只有一张图」
                    if status in ("attachment", "no_body") and not no_pdf:
                        for img_url in html_content_images(ahtml, it["url"]):
                            got = image_to_text(fetcher, img_url, config.get("ocr") or {})
                            if text_usable(got):
                                body = got[:8000]
                                status = "ok_img"
                                if not quiet:
                                    log(f"  ✓ 正文图 OCR 出 {cjk_len(body)} 字: "
                                        f"{it['title'][:36]}")
                                break
                    cur.execute(
                        """INSERT INTO articles
                           (url, site_id, site_name, column_name, title, published,
                            list_summary, body, body_len, fetched_at, http_status,
                            attachments, note)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                           ON CONFLICT(url) DO UPDATE SET
                             title=excluded.title, published=excluded.published,
                             body=excluded.body, body_len=excluded.body_len,
                             fetched_at=excluded.fetched_at,
                             http_status=excluded.http_status,
                             attachments=excluded.attachments,
                             note=excluded.note""",
                        (it["url"], site["id"], site["name"], col["name"], it["title"],
                         pub, it["summary"], body, len(body), now_iso(), status,
                         json.dumps(atts, ensure_ascii=False),
                         _status_note(status, body, ahtml, it["url"])))
                    known.add(it["url"])
                    new_count += 1
                except Exception as e:  # noqa: BLE001
                    log(f"  ! 正文失败 {it['url']}: {str(e)[:90]}")
            conn.commit()

    conn.close()
    return new_count


# --------------------------------------------------------------------------
# 本地模型分析
# --------------------------------------------------------------------------

CATEGORIES = ["讲座学术", "教务教学", "招生", "奖助学金", "竞赛科研",
              "行政通知", "就业实习", "其他"]

# 正文截断长度。先用大的；实测到后端是 CPU（提示处理明显慢）时自动降到小的，
# 否则每篇都会撞满超时——CPU 版处理 2400 字正文要 1 分多钟。
BODY_CAP_FAST = 2400
BODY_CAP_SLOW = 900
SLOW_PROMPT_TPS = 80   # GPU 版实测约 190 tok/s，CPU 版约 45

# 远程免费模型用的强约束 schema。实测：json_schema 比 json_object 又快又稳
# （json_object 在有思考链的模型上会把 max_tokens 烧光、content 返回空）。
CRAWL_SCHEMA = {
    "type": "object",
    "properties": {
        "summary":     {"type": "string"},
        "category":    {"type": "string", "enum": CATEGORIES},
        "audience":    {"type": "string"},
        "deadline":    {"type": ["string", "null"]},
        "importance":  {"type": "integer"},
        "fit":         {"type": "integer", "minimum": 0, "maximum": 100},
        "fit_reason":  {"type": "string"},
    },
    "required": ["summary", "category", "audience", "deadline", "importance",
                 "fit", "fit_reason"],
    "additionalProperties": False,
}

PROMPT_TMPL = """你是校园信息助理。读者是本校学生。阅读下面的学校通知或新闻，只输出一个 JSON 对象，不要输出任何其他文字、不要用 markdown 代码块。

JSON 字段说明：
- summary: 一句话摘要，不超过 40 个字，说清"什么事"
- category: 必须从这些里选一个 —— {cats}
- audience: 面向对象（如 本科生/研究生/教师/全院师生/全体），不确定填"未说明"
- deadline: 报名或材料提交的截止日期，格式 YYYY-MM-DD；没有明确日期则填 null
- importance: 对学生的普遍紧要程度，1（一般）到 5（很紧要）。涉及报名截止、奖学金评定、选课考试的给高分
- fit: 这条信息对普通本科生的有用程度，0-100 的整数。选课考试、奖助学金、竞赛、面向本科生的活动给高分；面向研究生、教师、科研项目申报、校级行政往来给低分
- fit_reason: 一句话说明 fit 的理由，不超过 20 个字

标题：{title}
所属栏目：{column}
发布日期：{date}
正文：
{body}
"""


class LLMClient:
    def __init__(self, cfg: dict):
        self.base = cfg["base_url"].rstrip("/")
        self.model = cfg["model"]
        self.key = cfg.get("api_key", "local")
        self.timeout = cfg.get("timeout", 180)
        self.last_timings: dict = {}

    def available(self) -> bool:
        try:
            req = urllib.request.Request(self.base + "/models")
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status == 200
        except Exception:  # noqa: BLE001
            return False

    def complete_json(self, prompt: str, max_tokens: int = 400) -> dict:
        payload = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": max_tokens,
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        }
        req = urllib.request.Request(
            self.base + "/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {self.key}"},
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            resp = json.load(r)
        self.last_timings = resp.get("timings") or {}
        content = resp["choices"][0]["message"].get("content") or ""
        return _loose_json(content)


def _loose_json(text: str) -> dict:
    text = text.strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    if start == -1:
        raise ValueError(f"模型未返回 JSON: {text[:200]}")
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return json.loads(text[start:i + 1])
    raise ValueError(f"JSON 不完整: {text[:200]}")


def analyze(config: dict, limit: int | None = None, since_days: int | None = None,
            redo: bool = False) -> int:
    llm_cfg = config["llm"]
    backend = llm_cfg.get("backend", "local")

    if backend == "free":
        from llm_remote import RemoteLLM, choose_chain
        # 有魔粒就用 V4-Pro，没有就只用 ¥0 的（用户 2026-10-18 的要求）
        chain, note = choose_chain(
            [tuple(x) for x in llm_cfg["chain"]],
            prefer_paid=llm_cfg.get("prefer_paid", True))
        log(f"链路决策：{note}")
        remote = RemoteLLM(
            chain=chain,
            min_interval=llm_cfg.get("min_interval", 1.0),
            timeout=llm_cfg.get("timeout", 120))
        max_tokens = llm_cfg.get("max_tokens", 800)

        def call(prompt: str) -> tuple[dict, str]:
            data, _text, err = remote.chat_json(
                prompt, schema=CRAWL_SCHEMA, temperature=0.2,
                max_tokens=max_tokens)
            if data is None:
                raise RuntimeError(err or "远程模型未返回 JSON")
            return data, remote.last_model

        label = " / ".join(f"{p}:{m}" for p, m in remote.chain)
        log(f"后端：免费路由（降级链 {label}）")
    else:
        local_cfg = llm_cfg.get("local") or llm_cfg
        llm = LLMClient(local_cfg)
        if not llm.available():
            log(f"本地模型不可用：{llm.base}（先跑 `llm serve`）")
            return 0

        def call(prompt: str) -> tuple[dict, str]:
            data = llm.complete_json(prompt)
            return data, llm.model

        label = llm.model
        log(f"后端：本地 {label}")

    conn = db_connect()
    cur = conn.cursor()
    sql = """SELECT a.url, a.title, a.column_name, a.published, a.body,
                    a.list_summary, a.http_status, a.attachments
             FROM articles a LEFT JOIN analysis an ON an.url = a.url
             WHERE (:redo OR an.url IS NULL OR an.error IS NOT NULL)"""
    params = {"redo": 1 if redo else 0}
    if since_days:
        cutoff = (datetime.now() - timedelta(days=since_days)).strftime("%Y-%m-%d")
        sql += " AND (a.published IS NULL OR a.published >= :cutoff)"
        params["cutoff"] = cutoff
    sql += " ORDER BY a.published DESC, a.fetched_at DESC"
    if limit:
        sql += f" LIMIT {int(limit)}"
    rows = cur.execute(sql, params).fetchall()

    if not rows:
        log("没有需要分析的文章")
        conn.close()
        return 0

    log(f"开始分析 {len(rows)} 篇…")
    done = 0
    t0 = time.time()
    body_cap = BODY_CAP_FAST          # 实测后端慢时会自动调小
    paced_checked = backend != "local"   # 远程后端不做本地速率探测
    for i, r in enumerate(rows, 1):
        body = (r["body"] or "").strip()
        if not body_is_usable(body):
            # 正文没拿到（微信验证页 / 附件型通知）：退而用列表页摘要。
            # 只用标题也还能做出可用的分类与面向对象判断，所以不跳过。
            body = (r["list_summary"] or "").strip()
        # 把附件名一并交给模型：附件名往往就是全部信息
        try:
            atts = json.loads(r["attachments"] or "[]")
        except (json.JSONDecodeError, TypeError):
            atts = []
        if atts:
            body += "\n（本文正文为附件：" + "、".join(a["name"] for a in atts) + "）"
        if cjk_len(body) < 20 and cjk_len(r["title"]) < 6:
            cur.execute("""INSERT OR REPLACE INTO analysis
                (url,summary,category,audience,deadline,importance,fit,fit_reason,
                 model,analyzed_at,error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                (r["url"], "", "其他", "未说明", None, 1, None, None, label,
                 now_iso(), "正文与标题均过短"))
            continue
        body = body[:body_cap]
        prompt = PROMPT_TMPL.format(
            cats="/".join(CATEGORIES), title=r["title"],
            column=r["column_name"] or "", date=r["published"] or "未知", body=body)
        try:
            d, used_model = call(prompt)
            # 本地后端：用第一条的实测速度判断 GPU/CPU，慢了自动缩短正文，
            # 否则每篇都要撞满超时
            if backend == "local" and not paced_checked:
                paced_checked = True
                pps = (llm.last_timings or {}).get("prompt_per_second") or 0
                if 0 < pps < SLOW_PROMPT_TPS:
                    body_cap = BODY_CAP_SLOW
                    log(f"⚠️ 提示处理仅 {pps:.0f} tok/s（GPU 版约 190）。"
                        f"模型很可能跑在 CPU 上，后续正文截到 {BODY_CAP_SLOW} 字以免超时。"
                        f"恢复速度：先 llm stop，再用 CUDA 版启动（不要用 build/bin 那个二进制）")
                elif pps:
                    log(f"   后端速度 {pps:.0f} tok/s，正常")
            cat = str(d.get("category") or "其他").strip()
            if cat not in CATEGORIES:
                cat = "其他"
            dl = d.get("deadline")
            if dl in ("", "null", "None"):
                dl = None
            else:
                dl = norm_date(str(dl)) or None
            imp = d.get("importance")
            imp = max(1, min(5, int(imp))) if str(imp).strip().isdigit() else 1
            fit = d.get("fit")
            fit = (max(0, min(100, int(fit)))
                   if str(fit).strip().lstrip("-").isdigit() else None)
            fit_reason = str(d.get("fit_reason") or "").strip()[:40] or None
            cur.execute("""INSERT OR REPLACE INTO analysis
                (url,summary,category,audience,deadline,importance,fit,fit_reason,
                 model,analyzed_at,error)
                VALUES (?,?,?,?,?,?,?,?,?,?,NULL)""",
                (r["url"], str(d.get("summary") or "").strip()[:120], cat,
                 str(d.get("audience") or "未说明").strip()[:40], dl, imp,
                 fit, fit_reason, used_model, now_iso()))
            done += 1
        except Exception as e:  # noqa: BLE001
            cur.execute("""INSERT OR REPLACE INTO analysis
                (url,summary,category,audience,deadline,importance,fit,fit_reason,
                 model,analyzed_at,error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                (r["url"], "", "其他", "未说明", None, 1, None, None, label,
                 now_iso(), str(e)[:200]))
            log(f"  ! {r['title'][:30]} 分析失败: {str(e)[:80]}")
        if i % 10 == 0 or i == len(rows):
            el = time.time() - t0
            log(f"  进度 {i}/{len(rows)}  用时 {el:.0f}s  "
                f"均速 {el / i:.1f}s/篇")
        conn.commit()

    if backend == "free":
        log(f"模型调用报账：{remote.summary()}")
    conn.close()
    return done


# --------------------------------------------------------------------------
# 日报
# --------------------------------------------------------------------------

CAT_ORDER = ["招生", "奖助学金", "教务教学", "竞赛科研", "讲座学术",
             "行政通知", "就业实习", "其他"]


MAX_REPORT_ATTACHMENTS = 6


def _row_attachments(row) -> list[dict]:
    try:
        keys = row.keys()
    except AttributeError:
        return []
    if "attachments" not in keys:
        return []
    try:
        return json.loads(row["attachments"] or "[]")
    except (json.JSONDecodeError, TypeError):
        return []


def _fit_score(row) -> tuple[int, str]:
    """日报用：优先模型 fit，旧数据回退到通用 importance（并标注）。"""
    fit = row["fit"] if "fit" in row.keys() else None
    if fit is not None:
        return int(fit), (row["fit_reason"] or "")
    imp = row["importance"] or 1
    return {5: 70, 4: 65, 3: 60, 2: 50, 1: 40}.get(int(imp), 55), "未评分（按通用重要度折算）"


def build_report(config: dict, days: int = 7, out_path: Path | None = None) -> Path:
    conn = db_connect()
    cur = conn.cursor()
    cutoff = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")
    rows = cur.execute("""
        SELECT a.*, an.summary, an.category, an.audience, an.deadline,
               an.importance, an.fit, an.fit_reason, an.model
        FROM articles a LEFT JOIN analysis an ON an.url = a.url
        WHERE a.published IS NULL OR a.published >= ?
        ORDER BY an.fit IS NULL, an.fit DESC, an.importance DESC, a.published DESC
    """, (cutoff,)).fetchall()

    today = datetime.now().strftime("%Y-%m-%d")
    soon = (datetime.now() + timedelta(days=7)).strftime("%Y-%m-%d")

    # 分组
    groups: dict[str, list] = {}
    deadlines: list = []
    for r in rows:
        cat = r["category"] or "未分析"
        groups.setdefault(cat, []).append(r)
        if r["deadline"] and today <= r["deadline"] <= soon:
            deadlines.append(r)

    L: list[str] = []
    L.append(f"# 校园通知日报 · {today}")
    L.append("")
    L.append(f"统计区间：近 {days} 天　|　条目 {len(rows)} 条　|　"
             f"站点 {len({r['site_id'] for r in rows})} 个　|　"
             f"生成时间 {now_iso()}")
    L.append("")
    L.append(f"> 数据来源：{'、'.join(sorted({r['site_name'] or r['site_id'] for r in rows}))}")
    L.append("> ★ 模型打的 fit 分（对本科生的有用程度，0–100）")
    # 按库里实际记录的模型归因（降级链可能不止一个模型参与）
    models = sorted({r["model"] for r in rows if r["model"]})
    if models:
        L.append(f"> 摘要与分类由 {'、'.join(models)} 生成，可能有误，请以原文为准。")
    else:
        L.append("> 摘要与分类由免费模型生成，可能有误，请以原文为准。")
    L.append("")

    if deadlines:
        L.append("## ⏰ 一周内截止")
        L.append("")
        L.append("| 截止 | 事项 | 面向 | 栏目 |")
        L.append("|---|---|---|---|")
        for r in sorted(deadlines, key=lambda x: x["deadline"]):
            left = (datetime.strptime(r["deadline"], "%Y-%m-%d").date()
                    - datetime.now().date()).days
            tag = f"**{r['deadline']}**（{left} 天后）" if left > 0 else f"**{r['deadline']}**（今天）"
            L.append(f"| {tag} | [{r['title']}]({r['url']}) | "
                     f"{r['audience'] or '—'} | {r['column_name']} |")
        L.append("")

    L.append("## 分类汇总")
    L.append("")
    for cat in CAT_ORDER:
        items = groups.get(cat) or []
        if not items:
            continue
        L.append(f"### {cat}（{len(items)}）")
        L.append("")
        items = sorted(items, key=lambda r: -_fit_score(r)[0])
        for r in items:
            score, reason = _fit_score(r)
            star = "★" * (5 if score >= 90 else 4 if score >= 75 else 3 if score >= 60
                          else 2 if score >= 40 else 1) + f" {score}"
            head = f"- **[{r['title']}]({r['url']})**"
            meta = []
            if r["published"]:
                meta.append(r["published"])
            meta.append(r["site_name"] or r["site_id"])
            meta.append(r["column_name"] or "")
            if r["audience"] and r["audience"] != "未说明":
                meta.append(f"面向{r['audience']}")
            if r["deadline"]:
                meta.append(f"截止 {r['deadline']}")
            L.append(head + (f"  {star}" if star else ""))
            if r["summary"]:
                L.append(f"  - {r['summary']}")
            if reason:
                L.append(f"  - ↳ {reason}")
            L.append(f"  - <sub>{' · '.join(x for x in meta if x)}</sub>")
            atts = _row_attachments(r)
            if r["note"]:
                L.append(f"  - 💬 {r['note']}")
            for a in atts[:MAX_REPORT_ATTACHMENTS]:
                L.append(f"  - 📎 [{a['name']}]({a['url']})")
            if len(atts) > MAX_REPORT_ATTACHMENTS:
                L.append(f"  - <sub>…另有 {len(atts) - MAX_REPORT_ATTACHMENTS} 个附件，"
                         f"见原文链接</sub>")
        L.append("")

    left_cats = [c for c in groups if c not in CAT_ORDER]
    for cat in left_cats:
        L.append(f"### {cat}（{len(groups[cat])}）")
        L.append("")
        for r in groups[cat]:
            L.append(f"- [{r['title']}]({r['url']})　<sub>{r['published'] or ''} "
                     f"{r['site_name'] or ''}</sub>")
        L.append("")

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    path = out_path or (REPORT_DIR / f"{today}.md")
    path.write_text("\n".join(L), encoding="utf-8")
    conn.close()
    return path


# --------------------------------------------------------------------------
# 展示
# --------------------------------------------------------------------------

def show_stats() -> None:
    conn = db_connect()
    cur = conn.cursor()
    total = cur.execute("SELECT COUNT(*) c FROM articles").fetchone()["c"]
    ana = cur.execute("SELECT COUNT(*) c FROM analysis WHERE error IS NULL").fetchone()["c"]
    print(f"文章总数 : {total}")
    print(f"已分析   : {ana}")
    print(f"数据库   : {DB_PATH}")
    print("\n按站点：")
    for r in cur.execute("""SELECT site_name, COUNT(*) c, MAX(published) latest
                            FROM articles GROUP BY site_id ORDER BY c DESC"""):
        print(f"  {r['site_name'] or '?':24s} {r['c']:5d} 条   最新 {r['latest'] or '—'}")
    print("\n按分类：")
    for r in cur.execute("""SELECT category, COUNT(*) c FROM analysis
                            WHERE error IS NULL GROUP BY category ORDER BY c DESC"""):
        print(f"  {r['category']:10s} {r['c']:5d}")
    print("\n最近 5 次抓取：")
    for r in cur.execute("SELECT * FROM run_log ORDER BY id DESC LIMIT 5"):
        print(f"  {r['started_at']}  新增 {r['new_articles']}  分析 {r['analyzed']}")
    conn.close()


def show_list(days: int = 3, limit: int = 60) -> None:
    conn = db_connect()
    cutoff = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")
    rows = conn.execute("""
        SELECT a.*, an.summary, an.category, an.importance, an.deadline
        FROM articles a LEFT JOIN analysis an ON an.url = a.url
        WHERE a.published IS NULL OR a.published >= ?
        ORDER BY a.published DESC LIMIT ?""", (cutoff, limit)).fetchall()
    for r in rows:
        print(f"{r['published'] or '----------'}  [{r['category'] or '未分析'}] "
              f"{r['title']}")
        print(f"            {r['site_name']} · {r['column_name']}  {r['url']}")
        if r["summary"]:
            print(f"            → {r['summary']}")
    print(f"\n共 {len(rows)} 条（近 {days} 天）")
    conn.close()


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def load_config() -> dict:
    if not CONFIG_PATH.exists():
        sys.exit(f"找不到配置文件 {CONFIG_PATH}")
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def main() -> None:
    ap = argparse.ArgumentParser(
        description="校园通知聚合：抓取 + 模型打分 + 日报")
    sub = ap.add_subparsers(dest="cmd")

    p = sub.add_parser("crawl", help="抓取文章入库")
    p.add_argument("--site", help="只抓某个站点 id（news/jwc/cs/ee）")
    p.add_argument("--refresh", action="store_true", help="连已入库的也重新抓正文")
    p.add_argument("--no-pdf", action="store_true",
                   help="不解析 PDF 附件（默认会下 PDF 提文字）")

    p = sub.add_parser("analyze", help="用本地模型分析未分析的文章")
    p.add_argument("--limit", type=int, help="最多分析多少篇")
    p.add_argument("--days", type=int, help="只分析最近 N 天的")
    p.add_argument("--redo", action="store_true", help="重做已分析过的")

    p = sub.add_parser("report", help="生成 Markdown 日报")
    p.add_argument("--days", type=int, default=7)
    p.add_argument("--out", help="输出路径")

    p = sub.add_parser("run", help="抓取 + 分析 + 日报")
    p.add_argument("--days", type=int, default=7, help="日报区间（默认 7 天）")
    p.add_argument("--analyze-days", type=int, default=30,
                   help="只分析最近 N 天的文章（默认 30）")
    p.add_argument("--limit", type=int, help="本次最多分析多少篇")
    p.add_argument("--site", help="只抓某个站点")
    p.add_argument("--no-pdf", action="store_true", help="不解析 PDF 附件")

    p = sub.add_parser("stats", help="看数据库概况")
    p = sub.add_parser("list", help="终端列出近期条目")
    p.add_argument("--days", type=int, default=3)
    p.add_argument("--limit", type=int, default=60)

    args = ap.parse_args()
    if not args.cmd:
        ap.print_help()
        return

    config = load_config()

    if args.cmd == "stats":
        show_stats()
    elif args.cmd == "list":
        show_list(args.days, args.limit)
    elif args.cmd == "report":
        path = build_report(config, args.days,
                            Path(args.out) if args.out else None)
        log(f"日报已生成：{path}")
    elif args.cmd == "crawl":
        t0 = time.time()
        n = crawl(config, only_site=args.site, refresh=args.refresh,
                  no_pdf=getattr(args, 'no_pdf', False))
        log(f"抓取完成：新增 {n} 篇，用时 {time.time() - t0:.0f}s")
    elif args.cmd == "analyze":
        n = analyze(config, limit=args.limit, since_days=args.days, redo=args.redo)
        log(f"分析完成：{n} 篇")
    elif args.cmd == "run":
        t0 = time.time()
        conn = db_connect()
        conn.execute("INSERT INTO run_log (started_at, note) VALUES (?,?)",
                     (now_iso(), "run"))
        conn.commit()
        conn.close()

        n = crawl(config, only_site=args.site,
                  no_pdf=getattr(args, 'no_pdf', False))
        a = analyze(config, limit=args.limit, since_days=args.analyze_days)
        path = build_report(config, args.days)

        conn = db_connect()
        conn.execute("""UPDATE run_log SET finished_at=?, new_articles=?, analyzed=?
                        WHERE id=(SELECT MAX(id) FROM run_log)""",
                     (now_iso(), n, a))
        conn.commit()
        conn.close()
        log(f"全部完成：新增 {n} 篇，分析 {a} 篇，用时 {time.time() - t0:.0f}s")
        log(f"日报：{path}")


if __name__ == "__main__":
    main()
