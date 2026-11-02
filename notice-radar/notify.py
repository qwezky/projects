#!/usr/bin/env python3
"""把一段文本推到 webhook（企业微信 / 钉钉 / 自建网关都行）。

刻意做得很小：抓取与打分不关心「怎么通知」，通知也不读数据库 —— 中间只传一段文本。

    python3 report_to_text.py | python3 notify.py --dry-run
    WEBHOOK_URL=https://example.com/hook python3 notify.py --file daily.md

环境变量：
    WEBHOOK_URL    必填（除非 --dry-run）
    WEBHOOK_TOKEN  可选，作为 Authorization: Bearer <token>
    WEBHOOK_FORMAT text（默认，{"text": ...}）/ wecom / raw

退出码：0 成功；2 参数或网络错误（可接进 cron 的告警）。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def build_payload(text: str, fmt: str) -> dict:
    if fmt == "raw":
        return {"text": text}
    if fmt == "wecom":
        # 企业微信群机器人
        return {"msgtype": "text", "text": {"content": text}}
    return {"text": text, "source": "notice-radar"}


def post(url: str, payload: dict, token: str | None, timeout: float,
         retries: int = 3) -> tuple[int, str]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    last = (0, "")
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read().decode("utf-8", "replace")
                return r.status, body
        except urllib.error.HTTPError as e:
            last = (e.code, e.read().decode("utf-8", "replace")[:300])
            if e.code in (400, 401, 403, 404):
                break                      # 重试没有意义
        except Exception as e:             # noqa: BLE001
            last = (0, str(e)[:300])
        if attempt < retries:
            time.sleep(min(2 ** attempt, 8))
    return last


def main() -> int:
    ap = argparse.ArgumentParser(description="把文本推到 webhook")
    ap.add_argument("--file", help="要发送的文件（默认读 stdin）")
    ap.add_argument("--url", default=os.environ.get("WEBHOOK_URL", ""))
    ap.add_argument("--token", default=os.environ.get("WEBHOOK_TOKEN", ""))
    ap.add_argument("--format", choices=["text", "wecom", "raw"], default="text")
    ap.add_argument("--max-chars", type=int, default=3500,
                    help="超过就截断（多数 IM 单条上限约 4096）")
    ap.add_argument("--timeout", type=float, default=15)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    text = (Path(args.file).read_text(encoding="utf-8") if args.file
            else sys.stdin.read()).strip()
    if not text:
        print("没有内容可发", file=sys.stderr)
        return 2
    if len(text) > args.max_chars:
        text = text[: args.max_chars] + "\n…（已截断）"

    payload = build_payload(text, args.format)
    if args.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2)[:2000])
        return 0
    if not args.url:
        print("没有 WEBHOOK_URL（或用 --url 指定）", file=sys.stderr)
        return 2

    status, body = post(args.url, payload, args.token or None, args.timeout)
    if 200 <= status < 300:
        print(f"已发送（HTTP {status}）")
        return 0
    print(f"发送失败（HTTP {status}）：{body}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
