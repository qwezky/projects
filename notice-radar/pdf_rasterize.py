#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 PDF 页面栅格化成 PNG，供 OCR 用。

本机没有 pdftoppm / ghostscript / mutool / node-canvas，所以用 PyMuPDF
（自带 MuPDF，桌面端 wheel 不依赖任何系统库）。用 uv 跑，不污染系统 Python：

    uv run --with pymupdf python pdf_rasterize.py in.pdf outdir --dpi 200 --max-pages 3

输出：outdir/page-1.png、page-2.png …；同时把成功生成的页数打到 stdout。

只栅格化前 N 页 —— 通知类 PDF 的关键信息几乎都在前几页，而全本栅格化
（培养方案动辄 40 页）既慢又没必要。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("outdir")
    ap.add_argument("--dpi", type=int, default=200,
                    help="栅格化 DPI（默认 200：中文小字够用，300 更清晰但慢一倍）")
    ap.add_argument("--max-pages", type=int, default=3)
    ap.add_argument("--max-pixels", type=int, default=1600 * 1600,
                    help="单页像素上限；超过就按比例缩小，防止超大页把显存打爆")
    ap.add_argument("--max-aspect", type=float, default=3.5,
                    help="长宽比超过它就竖向切片。实测 403x6360 的海报会让 VLM "
                         "陷入重复循环（结果重复 901 次同一行），切开就正常")
    a = ap.parse_args()

    try:
        import pymupdf  # noqa: PLC0415
    except ImportError:
        print("缺少 pymupdf（用 uv run --with pymupdf 跑）", file=sys.stderr)
        return 3

    pdf = Path(a.pdf)
    if not pdf.exists():
        print(f"找不到 {pdf}", file=sys.stderr)
        return 2
    out = Path(a.outdir)
    out.mkdir(parents=True, exist_ok=True)

    made = 0
    try:
        doc = pymupdf.open(pdf)
    except Exception as exc:  # noqa: BLE001
        print(f"打不开 PDF: {exc}", file=sys.stderr)
        return 4

    n = min(len(doc), max(1, a.max_pages))
    for i in range(n):
        page = doc[i]
        rect = page.rect
        zoom = a.dpi / 72.0
        # 先按 max_pixels 收敛缩放，**收敛后要重算 w/h**，否则后面的
        # 切片坐标会用旧尺寸去换算，导致 clip 越界
        if rect.width * zoom * rect.height * zoom > a.max_pixels:
            k = (a.max_pixels / (rect.width * zoom * rect.height * zoom)) ** 0.5
            zoom *= k
        w, h = rect.width * zoom, rect.height * zoom

        aspect = (h / w) if w else 0
        if aspect > a.max_aspect:
            # 竖向切片：每片高不超过 max_aspect×宽，片间留 6% 重叠避免切断行
            parts = int(-(-aspect // a.max_aspect))      # ceil
            slice_h = h / parts
            overlap = slice_h * 0.06
            for k in range(parts):
                y0 = max(0.0, k * slice_h - (overlap if k else 0))
                y1 = min(h, (k + 1) * slice_h + (overlap if k < parts - 1 else 0))
                clip = pymupdf.Rect(rect.x0,                    # 水平不变
                                    rect.y0 + y0 / zoom,        # 竖向切片
                                    rect.x1, rect.y0 + y1 / zoom)
                pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom),
                                      clip=clip, alpha=False)
                pix.save(out / f"page-{i + 1}-part-{k + 1}.png")
                made += 1
        else:
            pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False)
            pix.save(out / f"page-{i + 1}.png")
            made += 1
    print(made)
    return 0


if __name__ == "__main__":
    sys.exit(main())
