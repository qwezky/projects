#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「日志」安卓 App 的图标（纯标准库，无第三方依赖）。

产物（与既有工程同样的分层结构）：
  mipmap-*/ic_launcher.png            传统图标（含圆角背景）
  mipmap-*/ic_launcher_round.png      传统圆形图标
  mipmap-*/ic_launcher_foreground.png 自适应图标前景（只有图形，透明底）
  mipmap-*/ic_launcher_background.png 自适应图标背景（渐变）
  drawable-nodpi/ic_launcher_monochrome.png  Android 13+ 主题图标单色层
  mipmap-anydpi-v26/ic_launcher*.xml  自适应图标描述（手写，不在本脚本里）

图形语义：靛蓝底 + 一块圆角"页面" + 三行条目（每行一个琥珀色圆点 + 一条浅色横线），
第一行是更短更粗的"标题行" —— 一眼看出是"一条条记下来的日志"。
全部按 4 倍超采样后降采样，边缘不会锯齿。
"""
import os
import struct
import zlib

SS = 4  # 超采样倍数

BG_TOP = (0x1B, 0x22, 0x3A)       # 靛蓝
BG_BOTTOM = (0x0B, 0x0E, 0x1C)
PANEL = (0x26, 0x2E, 0x4E, 0xFF)  # 页面
DOT = (0xFF, 0xC1, 0x66, 0xFF)    # 琥珀（标记）
BAR = (0xC9, 0xD2, 0xFF, 0xFF)    # 浅靛（文字行）


def write_png(path, w, h, px):
    """px: bytearray RGBA，长度 w*h*4。"""
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)  # filter type 0
        raw += px[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def blank(n):
    return bytearray(n * n * 4)


def put(px, n, x, y, rgba):
    if 0 <= x < n and 0 <= y < n:
        i = (y * n + x) * 4
        px[i:i + 4] = bytes(rgba)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def fill_gradient(px, n, top=BG_TOP, bottom=BG_BOTTOM):
    for y in range(n):
        c = lerp(top, bottom, y / max(1, n - 1))
        for x in range(n):
            put(px, n, x, y, (c[0], c[1], c[2], 255))


def fill_round_rect(px, n, x0, y0, x1, y1, r, rgba):
    for y in range(max(0, y0), min(n, y1)):
        for x in range(max(0, x0), min(n, x1)):
            cx = None
            if x < x0 + r:
                cx = x0 + r
            elif x >= x1 - r:
                cx = x1 - r - 1
            cy = None
            if y < y0 + r:
                cy = y0 + r
            elif y >= y1 - r:
                cy = y1 - r - 1
            if cx is not None and cy is not None:
                if (x - cx) ** 2 + (y - cy) ** 2 > r * r:
                    continue
            put(px, n, x, y, rgba)


def draw_line(px, n, x0, y0, x1, y1, rgba, thick=1):
    """Bresenham 加粗。"""
    dx, dy = abs(x1 - x0), abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx - dy
    while True:
        for ox in range(-(thick // 2), thick // 2 + 1):
            for oy in range(-(thick // 2), thick // 2 + 1):
                put(px, n, x0 + ox, y0 + oy, rgba)
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 > -dy:
            err -= dy
            x0 += sx
        if e2 < dx:
            err += dx
            y0 += sy


def draw_dot(px, n, cx, cy, r, rgba):
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                put(px, n, x, y, rgba)


def panel_geometry(n, inset_ratio=0.20, top_ratio=0.26, bottom_ratio=0.74):
    """面板矩形（相对尺寸），保证在自适应图标的安全区内。"""
    x0 = int(n * inset_ratio)
    x1 = n - x0
    y0 = int(n * top_ratio)
    y1 = int(n * bottom_ratio)
    return x0, y0, x1, y1


def rows(n, x0, y0, x1, y1):
    """三行条目的几何：每行 (圆点圆心, 圆点半径, 横线起止, 粗细)。"""
    w, h = x1 - x0, y1 - y0
    out = []
    for i, (ry, length) in enumerate(((0.28, 0.52), (0.52, 0.78), (0.76, 0.66))):
        cy = y0 + int(ry * h)
        r = max(2, int(n * (0.016 if i else 0.018)))
        dx = x0 + int(0.14 * w)
        bx0 = x0 + int(0.26 * w)
        bx1 = x0 + int((0.26 + length * 0.62) * w)
        thick = max(2, int(n * (0.022 if i == 0 else 0.016)))
        out.append((dx, cy, r, bx0, bx1, thick))
    return out


def make_background(n):
    px = blank(n)
    fill_gradient(px, n)
    return px


def make_foreground(n):
    """自适应图标前景：只有面板与三行条目，透明底。图形控制在中心 66%。"""
    px = blank(n)
    x0, y0, x1, y1 = panel_geometry(n, inset_ratio=0.26, top_ratio=0.30, bottom_ratio=0.70)
    fill_round_rect(px, n, x0, y0, x1, y1, max(2, int(n * 0.035)), PANEL)
    for ddx, cy, r, bx0, bx1, thick in rows(n, x0, y0, x1, y1):
        draw_dot(px, n, ddx, cy, r, DOT)
        draw_line(px, n, bx0, cy, bx1, cy, BAR, thick)
    return px


def make_legacy(n, round_icon=False):
    """传统图标：背景铺满（圆形版裁成圆），再把前景画上去。"""
    px = make_background(n)
    if round_icon:
        r = n // 2
        for y in range(n):
            for x in range(n):
                if (x - r) ** 2 + (y - r) ** 2 > r * r:
                    put(px, n, x, y, (0, 0, 0, 0))
    else:
        rr = int(n * 0.22)
        for y in range(n):
            for x in range(n):
                corner = False
                cx = cy = None
                if x < rr: cx = rr
                elif x >= n - rr: cx = n - rr - 1
                if y < rr: cy = rr
                elif y >= n - rr: cy = n - rr - 1
                if cx is not None and cy is not None and (x - cx) ** 2 + (y - cy) ** 2 > rr * rr:
                    corner = True
                if corner:
                    put(px, n, x, y, (0, 0, 0, 0))

    fg = make_foreground(n)
    for i in range(0, n * n * 4, 4):
        a = fg[i + 3]
        if a == 0:
            continue
        if a == 255:
            px[i:i + 4] = fg[i:i + 4]
        else:
            t = a / 255.0
            for k in range(3):
                px[i + k] = int(round(px[i + k] * (1 - t) + fg[i + k] * t))
            px[i + 3] = 255
    return px


def make_monochrome(n):
    """Android 13+ 主题图标单色层：只要形状，颜色由系统决定（系统只取 alpha）。"""
    px = blank(n)
    solid = (0xFF, 0xFF, 0xFF, 0xFF)
    x0, y0, x1, y1 = panel_geometry(n, inset_ratio=0.28, top_ratio=0.32, bottom_ratio=0.68)
    fill_round_rect(px, n, x0, y0, x1, y1, max(2, int(n * 0.03)), solid)
    # 条目用"挖空"表示：系统只取 alpha，所以这里把线条画成全透明
    for ddx, cy, r, bx0, bx1, thick in rows(n, x0, y0, x1, y1):
        draw_dot(px, n, ddx, cy, r, (0, 0, 0, 0))
        draw_line(px, n, bx0, cy, bx1, cy, (0, 0, 0, 0), thick)
    return px


def downsample(px, n, factor):
    m = n // factor
    out = bytearray(m * m * 4)
    area = factor * factor
    for y in range(m):
        for x in range(m):
            r = g = b = a = 0
            for dy in range(factor):
                for dx in range(factor):
                    i = ((y * factor + dy) * n + (x * factor + dx)) * 4
                    r += px[i]; g += px[i + 1]; b += px[i + 2]; a += px[i + 3]
            o = (y * m + x) * 4
            out[o] = r // area
            out[o + 1] = g // area
            out[o + 2] = b // area
            out[o + 3] = a // area
    return m, out


DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

ADAPTIVE_FOREGROUND = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "app", "src", "main", "res")
    total = 0
    for folder, base in DENSITIES.items():
        n = base * SS
        m, px = downsample(make_legacy(n, round_icon=False), n, SS)
        write_png(os.path.join(root, folder, "ic_launcher.png"), m, m, px)
        m, px = downsample(make_legacy(n, round_icon=True), n, SS)
        write_png(os.path.join(root, folder, "ic_launcher_round.png"), m, m, px)
        fn = ADAPTIVE_FOREGROUND[folder]
        m, px = downsample(make_foreground(fn * SS), fn * SS, SS)
        write_png(os.path.join(root, folder, "ic_launcher_foreground.png"), m, m, px)
        m, px = downsample(make_background(fn * SS), fn * SS, SS)
        write_png(os.path.join(root, folder, "ic_launcher_background.png"), m, m, px)
        m, px = downsample(make_monochrome(fn * SS), fn * SS, SS)
        write_png(os.path.join(root, "drawable-nodpi", "ic_launcher_monochrome.png"), m, m, px)
        total += 5
        print("  %-18s %dpx 完成" % (folder, base))
    print("共写出 %d 个 PNG" % total)


if __name__ == "__main__":
    main()
