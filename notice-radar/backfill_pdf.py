#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把正文偏薄、但有 PDF 附件的文章补抓一次（历史数据的一次性补齐）。

往后新抓的文章不用跑这个 —— `notice_radar.py crawl` 遇到正文不可用会自动提 PDF。
这个脚本只处理"当年抓的时候还没有 PDF 提取功能"的那批。

用法:
    python3 backfill_pdf.py            # 补
    python3 backfill_pdf.py --dry-run  # 只看会补哪些
    python3 backfill_pdf.py --min-cjk 100   # 只要 PDF 文本够长才采纳
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time

import notice_radar as sc


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--min-cjk", type=int, default=0,
                    help="已废弃：改用 text_usable（英文海报也算可用）")
    ap.add_argument("--limit", type=int, help="最多补多少篇")
    args = ap.parse_args()

    cfg = sc.load_config()
    fetcher = sc.Fetcher(cfg["crawl"])
    conn = sc.db_connect()
    cur = conn.cursor()

    rows = cur.execute(
        "SELECT url, title, body, body_len, attachments FROM articles "
        "WHERE (body_len IS NULL OR body_len < 200) "
        "AND attachments IS NOT NULL AND attachments != '[]'").fetchall()
    if args.limit:
        rows = rows[:args.limit]

    print(f"候选 {len(rows)} 篇（正文<200字且有附件）")
    done = skipped = 0
    t0 = time.time()
    for i, r in enumerate(rows, 1):
        try:
            atts = json.loads(r["attachments"] or "[]")
        except (json.JSONDecodeError, TypeError):
            atts = []
        pdfs = [a for a in atts if a["url"].lower().endswith(".pdf")]
        if not pdfs:
            skipped += 1
            continue
        if args.dry_run:
            print(f"  [计划] {r['title'][:50]} <- {pdfs[0]['name'][:40]}")
            continue

        text = sc.pdf_to_text(fetcher, pdfs[0]["url"],
                              ocr_cfg=cfg.get("ocr"))
        got = sc.cjk_len(text)
        if not sc.text_usable(text):
            skipped += 1
            if got:
                print(f"  · {r['title'][:40]}：PDF 仅 {got} 字，跳过")
            continue

        base = (r["body"] or "").strip()
        if base:
            body = f"{base}\n\n【附件正文：{pdfs[0]['name']}】\n{text}"
        else:
            body = text
        body = body[:12000]
        cur.execute(
            "UPDATE articles SET body=?, body_len=?, http_status=? WHERE url=?",
            (body, sc.cjk_len(body), "ok_pdf", r["url"]))
        done += 1
        print(f"  ✓ [{i}/{len(rows)}] {r['title'][:38]}  +{got} 字")
        if done % 10 == 0:
            conn.commit()
    conn.commit()
    conn.close()
    print(f"\n补齐 {done} 篇，跳过 {skipped} 篇，用时 {time.time()-t0:.0f}s")


if __name__ == "__main__":
    sys.exit(main())
