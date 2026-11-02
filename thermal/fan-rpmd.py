#!/usr/bin/env python3
"""常驻：每秒把风扇转速写进 /run/fan-rpm（world-readable）。

为什么要它
----------
本机风扇转速在 EC RAM 里：FANS = 偏移 6、FA2S = 偏移 254（值 ×100 = rpm）。
但入口只有 root 能碰：内核自带模块 ec_sys 把 EC RAM 暴露成
/sys/kernel/debug/ec/ec0/io，而该文件是 0400 root、外层 /sys/kernel/debug 是 0700。

挂一个 root 常驻小程序，把**只有转速这几个数**写到一个所有人可读的文件里，
工作流与交互式命令就都能免提权读到 —— 不用放宽 debugfs 权限，也不用加免密 sudo。

输出（一行，空格分隔）
---------------------
    <风扇1rpm> <风扇2rpm> <包温°C> <unix 时间戳>
写入 /run/fan-rpm（0644，先写 .tmp 再 os.replace，读者不会看到半行）。

消费者必须校验时间戳：超过几秒没更新就当作"读不到"，回退到别的策略。

用法
----
    sudo python3 fan-rpmd.py          # 前台调试
    （常驻请用 fan-rpmd.service）

环境变量：
    FAN_RPM_FILE      输出路径，默认 /run/fan-rpm
    FAN_RPM_INTERVAL  采样间隔秒，默认 1.0
"""
from __future__ import annotations

import os
import subprocess
import sys
import time

EC = "/sys/kernel/debug/ec/ec0/io"
OUT = os.environ.get("FAN_RPM_FILE", "/run/fan-rpm")
INTERVAL = float(os.environ.get("FAN_RPM_INTERVAL", "1.0"))
OFF_FAN1 = 6      # FANS  ← DSDT OperationRegion(ERAX, SystemMemory, 0xFF00D400, 0xFF) 字段表
OFF_FAN2 = 254    # FA2S


def ensure_ec() -> bool:
    """确保 ec_sys 已加载、EC RAM 文件在（只读，不带 write_support）。"""
    if os.path.exists(EC):
        return True
    subprocess.run(["modprobe", "ec_sys"], check=False)
    time.sleep(0.5)
    return os.path.exists(EC)


def pkg_temp() -> float | None:
    """coretemp 的 Package id 0（按 name/label 找，不写死 hwmonN）。"""
    try:
        names = os.listdir("/sys/class/hwmon")
    except OSError:
        return None
    for n in names:
        base = f"/sys/class/hwmon/{n}"
        try:
            with open(f"{base}/name", encoding="ascii", errors="replace") as fh:
                if fh.read().strip() != "coretemp":
                    continue
        except OSError:
            continue
        try:
            entries = os.listdir(base)
        except OSError:
            continue
        for e in entries:
            if not e.endswith("_label"):
                continue
            try:
                with open(f"{base}/{e}", encoding="ascii", errors="replace") as fh:
                    if fh.read().strip() != "Package id 0":
                        continue
                with open(f"{base}/{e[:-6]}_input", encoding="ascii") as fh:
                    return int(fh.read().strip()) / 1000.0
            except (OSError, ValueError):
                continue
        try:
            with open(f"{base}/temp1_input", encoding="ascii") as fh:
                return int(fh.read().strip()) / 1000.0
        except (OSError, ValueError):
            continue
    return None


def ec_byte(fh, off: int) -> int | None:
    try:
        fh.seek(off)
        b = fh.read(1)
        return b[0] if b else None
    except OSError:
        return None


def write_out(r1: int | None, r2: int | None, t: float | None) -> None:
    line = (f"{'' if r1 is None else r1 * 100} "
            f"{'' if r2 is None else r2 * 100} "
            f"{'' if t is None else f'{t:.1f}'} {int(time.time())}\n")
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(line)
    os.chmod(tmp, 0o644)
    os.replace(tmp, OUT)


def main() -> int:
    if os.geteuid() != 0:
        print("需要 root（EC RAM 入口只有 root 能读）", file=sys.stderr)
        return 1
    if not ensure_ec():
        print(f"找不到 {EC} —— ec_sys 没加载成功？", file=sys.stderr)
        return 1

    write_out(None, None, pkg_temp())          # 先建出来，固定权限
    fh = open(EC, "rb", buffering=0)
    print(f"fan-rpmd: 每 {INTERVAL}s 写 {OUT}（ctrl-c 退出）", file=sys.stderr)

    while True:
        if not os.path.exists(EC):             # 模块被卸载 / 设备重扫
            try:
                fh.close()
            except OSError:
                pass
            if not ensure_ec():
                time.sleep(5)
                continue
            fh = open(EC, "rb", buffering=0)
        try:
            write_out(ec_byte(fh, OFF_FAN1), ec_byte(fh, OFF_FAN2), pkg_temp())
        except OSError as exc:                 # noqa: PERF203
            print(f"fan-rpmd: 写入失败 {exc}", file=sys.stderr)
        time.sleep(INTERVAL)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(0)
