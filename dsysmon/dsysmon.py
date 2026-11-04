#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dsysmon —— 手动调用的服务器数据监测面板（只读）

设计约束：
  * 纯 Python3 标准库，无第三方依赖，不需要 sudo；
  * 只读：只读取 /proc、/sys、df、ps 等公开信息，不修改任何系统状态；
  * 一次性快照为默认模式，--watch 才持续刷新（永不后台自启）。

用法见 --help。
"""

from __future__ import annotations

import argparse
import base64
import glob
import html
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = "1.0"
PROG = "dsysmon"
STATE_DIR = os.path.expanduser("~/.local/state/server-monitor")

# ---------------------------------------------------------------- 采集工具


def cmd(args, timeout=4.0, keep_partial=False):
    """执行外部命令，失败/超时返回 None（监测工具不能因单项失败而崩）。

    keep_partial=True 时保留非零退出码的输出：du / lsof 这类命令在当前用户
    无权限读取某些子目录时会返回非零，但输出对其余部分依然有效。
    """
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    if p.returncode != 0 and not keep_partial:
        return None
    return p.stdout


def read_text(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return None


def read_int(path):
    txt = read_text(path)
    if txt is None:
        return None
    try:
        return int(txt.strip())
    except ValueError:
        return None


def shq(s):
    """仅用于展示命令行；不做 shell 转义（不拼接执行）。"""
    return str(s)


# ---------------------------------------------------------------- 解析函数


def parse_os_release():
    txt = read_text("/etc/os-release") or ""
    for line in txt.splitlines():
        if line.startswith("PRETTY_NAME="):
            return line.split("=", 1)[1].strip().strip('"')
    return "unknown"


def parse_meminfo():
    txt = read_text("/proc/meminfo") or ""
    info = {}
    for line in txt.splitlines():
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        m = re.search(r"(\d+)", v)
        if m:
            info[k] = int(m.group(1)) * 1024  # kB -> bytes
    total = info.get("MemTotal", 0)
    avail = info.get("MemAvailable")
    if avail is None:
        avail = info.get("MemFree", 0) + info.get("Buffers", 0) + info.get("Cached", 0)
    st = info.get("SwapTotal", 0)
    sf = info.get("SwapFree", 0)
    return {
        "total": total,
        "available": avail,
        "used": max(0, total - avail),
        "used_pct": (100.0 * (total - avail) / total) if total else 0.0,
        "cached": info.get("Cached", 0),
        "buffers": info.get("Buffers", 0),
        "dirty": info.get("Dirty", 0),
        "swap_total": st,
        "swap_used": max(0, st - sf),
        "swap_pct": (100.0 * (st - sf) / st) if st else 0.0,
    }


def parse_cpu_times():
    """读取 /proc/stat 的总体与逐核 CPU 时间，用于计算两次采样的差值。"""
    txt = read_text("/proc/stat")
    if not txt:
        return None
    total = None
    cores = []
    for line in txt.splitlines():
        if not line.startswith("cpu"):
            continue
        parts = line.split()
        if len(parts) < 8:
            continue
        try:
            vals = [int(x) for x in parts[1:9]]
        except ValueError:
            continue
        rec = {"label": parts[0], "times": vals, "sum": sum(vals), "idle": vals[3] + vals[4]}
        if parts[0] == "cpu":
            total = rec
        else:
            cores.append(rec)
    if total is None:
        return None
    return {"total": total, "cores": cores}


def cpu_delta(a, b):
    """两次采样之间的使用率（%）。"""
    if not a or not b:
        return None
    out = {}
    for key, rec_key in (("total", "total"),):
        sa, sb = a[rec_key], b[rec_key]
        ds = sb["sum"] - sa["sum"]
        di = sb["idle"] - sa["idle"]
        out[key] = (100.0 * (ds - di) / ds) if ds > 0 else 0.0
    cores = []
    for ca, cb in zip(a["cores"], b["cores"]):
        ds = cb["sum"] - ca["sum"]
        di = cb["idle"] - ca["idle"]
        cores.append((100.0 * (ds - di) / ds) if ds > 0 else 0.0)
    out["cores"] = cores
    return out


def parse_netdev():
    """返回 {iface: (rx_bytes, tx_bytes)}，跳过 lo。"""
    txt = read_text("/proc/net/dev") or ""
    out = {}
    for line in txt.splitlines():
        if ":" not in line:
            continue
        name, rest = line.split(":", 1)
        name = name.strip()
        if name == "lo":
            continue
        f = rest.split()
        if len(f) < 9:
            continue
        try:
            out[name] = (int(f[0]), int(f[8]))
        except ValueError:
            continue
    return out


def net_delta(a, b, dt):
    """两次采样之间的速率（bytes/s）。"""
    if not a or not b or dt <= 0:
        return {}
    rates = {}
    for name in set(b) & set(a):
        rx = b[name][0] - a[name][0]
        tx = b[name][1] - a[name][1]
        if rx < 0 or tx < 0:  # 计数器回绕/接口重置
            continue
        rates[name] = (rx / dt, tx / dt)
    return rates


def parse_loadavg():
    txt = read_text("/proc/loadavg") or ""
    try:
        parts = txt.split()
        return float(parts[0]), float(parts[1]), float(parts[2])
    except (IndexError, ValueError):
        return None, None, None


def parse_uptime():
    txt = read_text("/proc/uptime") or ""
    try:
        return float(txt.split()[0])
    except (IndexError, ValueError):
        return None


def parse_proc_times():
    """第一次采样：每个进程的累计 CPU tick + RSS。用于差分算真实 CPU%。"""
    out = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        txt = read_text("/proc/%s/stat" % entry)
        if not txt:
            continue
        rp = txt.rfind(")")
        if rp < 0:
            continue
        try:
            rest = txt[rp + 1:].split()
            out[int(entry)] = {
                "ticks": int(rest[11]) + int(rest[12]),  # utime + stime
                "rss_pages": int(rest[21]),
                "comm": txt[txt.find("(") + 1:rp],
            }
        except (ValueError, IndexError):
            continue
    return out


def is_kernel_thread(pid):
    """内核线程：没有可执行文件、命令行为空。它们永远是 0% CPU，
    只有给进程榜添乱的作用，直接排除。"""
    try:
        os.readlink("/proc/%d/exe" % pid)
    except OSError:
        pass
    else:
        return False
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as fh:
            return fh.read(1) == b""
    except OSError:
        return True


def parse_procs(tops, first, second, dt, mem_total):
    """CPU% 用两次 /proc/<pid>/stat 差分得到（ps 的 pcpu 是进程生命期均值，
    短命进程会算出 200% 这类假值，也会把 ps 自己排进榜首）。"""
    txt = read_text("/proc/loadavg") or ""
    try:
        running = int(txt.split()[3].split("/")[0])
    except (IndexError, ValueError):
        running = None

    page = os.sysconf("SC_PAGE_SIZE") if hasattr(os, "sysconf") else 4096
    hz = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
    args = {}
    out = cmd(["ps", "-eo", "pid,args"], timeout=6)
    if out:
        for line in out.splitlines()[1:]:
            f = line.split(None, 1)
            if len(f) == 2 and f[0].isdigit():
                args[int(f[0])] = f[1].strip()

    rows = []
    for pid, b in (second or {}).items():
        a = (first or {}).get(pid)
        ticks = b["ticks"] - a["ticks"] if a else 0
        pcpu = 100.0 * (ticks / hz) / dt if dt > 0 else 0.0
        rss = b["rss_pages"] * page
        comm = b["comm"].strip("()")
        if not comm or comm.startswith("["):
            continue
        if is_kernel_thread(pid):  # 内核线程恒为 0%，不该占榜位
            continue
        rows.append(
            {
                "pid": pid,
                "comm": comm,
                "pcpu": max(0.0, pcpu),
                "rss": rss,
                "pmem": (100.0 * rss / mem_total) if mem_total else 0.0,
                "args": args.get(pid, comm),
            }
        )
    return {
        "count": running,
        "top_cpu": sorted(rows, key=lambda r: r["pcpu"], reverse=True)[:tops],
        "top_mem": sorted(rows, key=lambda r: r["rss"], reverse=True)[:tops],
    }


REAL_FS_SKIP = {
    "tmpfs", "devtmpfs", "squashfs", "overlay", "efivarfs", "ramfs",
    "autofs", "proc", "sysfs", "cgroup", "cgroup2", "tracefs", "debugfs",
    "securityfs", "pstore", "configfs", "fusectl", "bpf", "mqueue", "hugetlbfs",
}


def parse_disks(all_mounts=False):
    """真实块设备的容量与 inode 用量（跳过伪文件系统）。"""
    inodes = {}
    out = cmd(["df", "-i", "-P", "-B1"], timeout=5)
    if out:
        for line in out.splitlines()[1:]:
            f = line.split()
            if len(f) < 6:
                continue
            try:
                inodes[f[5]] = float(f[4].rstrip("%"))
            except ValueError:
                continue

    mounts = []
    out = cmd(["df", "-P", "-B1", "-x", "tmpfs", "-x", "devtmpfs", "-x", "squashfs",
               "-x", "overlay", "-x", "efivarfs"], timeout=5)
    if out:
        for line in out.splitlines()[1:]:
            f = line.split()
            if len(f) < 6:
                continue
            dev = f[0]
            if dev in ("none", "-") or f[0].startswith("shm"):
                continue
            try:
                size, used, avail = int(f[1]), int(f[2]), int(f[3])
                pct = float(f[4].rstrip("%"))
            except ValueError:
                continue
            mount = f[5]
            if mount == "/" or any(mount == p for p in ("/boot", "/home", "/var", "/opt", "/srv", "/data")):
                mounts.append(
                    {
                        "device": dev,
                        "mount": mount,
                        "size": size,
                        "used": used,
                        "avail": avail,
                        "used_pct": pct,
                        "inode_pct": inodes.get(mount),
                    }
                )

    io = {}
    txt = read_text("/proc/diskstats")
    if txt:
        for line in txt.splitlines():
            f = line.split()
            if len(f) < 14:
                continue
            name = f[2]
            if re.search(r"\d+$", name) and re.match(r"^(nvme\d+n\d+|sd[a-z]+|vd[a-z]+)", name):
                continue  # 只留整盘，跳过分区
            if not re.match(r"^(nvme\d+n\d+|sd[a-z]+|vd[a-z]+|mmcblk\d+|dm-\d+)$", name):
                continue
            try:
                io[name] = {
                    "read_bytes": int(f[5]) * 512,
                    "write_bytes": int(f[9]) * 512,
                }
            except ValueError:
                continue

    du_root = None
    if shutil.which("du"):
        du_root = {"path": "/", "entries": [], "error": None}

    total = sum(m["size"] for m in mounts if m["mount"] != "/boot/efi") or 1
    used = sum(m["used"] for m in mounts if m["mount"] != "/boot/efi")
    real = []
    seen = set()
    for m in mounts:
        if m["mount"] in seen:
            continue
        seen.add(m["mount"])
        real.append(m)
        if not all_mounts and len(real) >= 12:
            break
    return {
        "mounts": real,
        "io": io,
        "root_scan": du_root,
        "aggregate_pct": 100.0 * used / total,
    }


def fill_root_scan(disks, timeout=20.0):
    """可选的 `du -x -d1 /`（--disk-detail 才跑，默认关闭以免拖慢）。"""
    scan = disks.get("root_scan")
    if not scan or not shutil.which("du"):
        return
    out = cmd(["du", "-x", "-d1", "-B1", "/"], timeout=timeout, keep_partial=True)
    if out is None:
        scan["error"] = "du 不可用或超时（%.0fs）" % timeout
        return
    entries = []
    for line in out.splitlines():
        f = line.split("\t") if "\t" in line else line.split(None, 1)
        if len(f) != 2:
            continue
        try:
            size = int(f[0])
        except ValueError:
            continue
        path = f[1].strip()
        if path == "/":
            continue
        entries.append({"path": path, "bytes": size})
    scan["entries"] = sorted(entries, key=lambda e: e["bytes"], reverse=True)[:10]


def parse_sockets():
    """从 /proc/net 统计 TCP 连接状态（不需要 ss，也不需要额外权限）。"""
    mapping = {
        "01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV", "04": "FIN_WAIT1",
        "05": "FIN_WAIT2", "06": "TIME_WAIT", "07": "CLOSE", "08": "CLOSE_WAIT",
        "09": "LAST_ACK", "0A": "LISTEN", "0B": "CLOSING", "0C": "NEW_SYN_RECV",
    }
    tcp = {"total": 0, "by_state": {}, "listening_ports": [], "established_ports": []}
    for path in ("/proc/net/tcp", "/proc/net/tcp6"):
        txt = read_text(path)
        if not txt:
            continue
        for line in txt.splitlines()[1:]:
            f = line.split()
            if len(f) < 4:
                continue
            try:
                local_port = int(f[1].split(":")[1], 16)
                st = mapping.get(f[3].upper(), f[3])
            except (IndexError, ValueError):
                continue
            tcp["total"] += 1
            tcp["by_state"][st] = tcp["by_state"].get(st, 0) + 1
            if st == "LISTEN" and local_port not in tcp["listening_ports"]:
                tcp["listening_ports"].append(local_port)

    out = cmd(["ss", "-tun", "-H"], timeout=4)
    if out:
        for line in out.splitlines():
            f = line.split()
            if len(f) < 6:
                continue
            try:
                peer = f[5]
                if ":" not in peer:
                    continue
                port = int(peer.rsplit(":", 1)[1])
            except (IndexError, ValueError):
                continue
            if port not in (0, 80, 443) and port not in tcp["established_ports"]:
                tcp["established_ports"].append(port)
    udp_total = None
    udp = read_text("/proc/net/udp")
    if udp:
        udp_total = max(0, len(udp.splitlines()) - 1)
    tcp["udp_total"] = udp_total
    tcp["listening_ports"].sort()
    # 32768 以上是内核临时端口，出现在 LISTEN 里通常是正常连接的本地端，
    # 不是"服务在监听"，列出来只会误导
    stable = [p for p in tcp["listening_ports"] if p < 32768]
    tcp["ephemeral_listen"] = len(tcp["listening_ports"]) - len(stable)
    tcp["listening_ports"] = stable
    tcp["established_ports"] = [p for p in tcp["established_ports"] if p < 32768]
    return tcp


def temp_rank(t):
    """温度重要性排序：CPU 最先，其次是 NVMe / 无线 / PCH，其余靠后。"""
    chip = (t.get("chip") or "").lower()
    label = (t.get("label") or "").lower()
    if "coretemp" in chip:
        return (0, label)
    if "x86_pkg" in chip or "pch" in chip:
        return (1, chip)
    if "nvme" in chip:
        return (2, chip)
    if "iwlwifi" in chip or "wifi" in chip:
        return (3, chip)
    if "acpi" in chip or "battery" in chip or "b0d4" in chip:
        return (9, chip)
    return (5, chip + label)


def parse_temps():
    """温度：优先 lm-sensors（有 label、有临界值），否则 /sys/class/thermal。

    只保留有物理意义的读数：丢弃 <0°C 的占位值、ACPI 占位项，以及同名重复项。
    """
    out = []
    sensors = shutil.which("sensors")
    if sensors:
        txt = cmd([sensors, "-j"], timeout=6)
        if txt:
            try:
                data = json.loads(txt)
            except ValueError:
                data = None
            if isinstance(data, dict):
                for chip, val in data.items():
                    if not isinstance(val, dict):
                        continue
                    for feature, fv in val.items():
                        if not isinstance(fv, dict):
                            continue
                        # sensors -j 的形状：chip -> feature -> "tempN_input": 44.0
                        # 温度是叶子键的数值，"tempN_crit" 等同级键是它的临界值。
                        for key, raw in fv.items():
                            low = key.lower()
                            if not low.startswith("temp") or not low.endswith("_input"):
                                continue
                            base = key[: -len("_input")]  # 形如 temp1
                            try:
                                cur = float(raw)
                            except (TypeError, ValueError):
                                continue
                            if not (0 < cur < 200):  # 丢弃 0.1°C / 负值这类占位读数
                                continue
                            crit = None
                            for ck in (base + "_crit", base + "_max", base + "_emergency"):
                                v = fv.get(ck)
                                if isinstance(v, (int, float)) and 0 < float(v) < 250:
                                    crit = float(v)
                                    break
                            out.append({"chip": chip, "label": feature, "celsius": cur, "crit": crit})
    if not out:
        for zone in sorted(glob.glob("/sys/class/thermal/thermal_zone*")):
            cur = read_int(os.path.join(zone, "temp"))
            if cur is None:
                continue
            name = (read_text(os.path.join(zone, "type")) or os.path.basename(zone)).strip()
            if 100 < cur < 200000:
                out.append({"chip": "thermal", "label": name, "celsius": cur / 1000.0, "crit": None})

    # 同名同值去重（同一传感器常被 hwmon 与 thermal 各报一次）
    seen = set()
    uniq = []
    for t in sorted(out, key=temp_rank):
        key = (round(t["celsius"], 1), (t.get("label") or "").lower()[:12])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(t)

    # 只在 lm-sensors 完全缺失时退回 ACPI 温度区，避免把 SEN1..5 / B0D4
    # 这类 i915/ACPI 占位读数混进面板
    return uniq


FAN_RPM_FILE = "/run/fan-rpm"


def parse_fans(snap_ts=None, stale_after=10.0):
    """风扇转速。

    本机风扇由 EC 独占控制（没有 pwm、没有 platform_profile，ideapad 的 fan_mode
    是空壳），转速只能从 EC RAM 读；而读 EC RAM 要 root，所以由 root 常驻服务
    `fan-rpmd` 每秒把值写进 world-readable 的 /run/fan-rpm：

        <风扇1rpm> <风扇2rpm> <包温°C> <unix 时间戳>

    这里只读那个文件。**带时间戳的新鲜度检查是必须的**：服务挂了或没装时文件
    可能还在（tmpfs 重启即清），不检查就会一直显示一个死值。
    """
    txt = read_text(FAN_RPM_FILE)
    if not txt:
        return {"available": False, "reason": "%s 不存在或不可读（需要 fan-rpmd 服务）" % FAN_RPM_FILE}
    f = txt.split()
    if len(f) < 4:
        return {"available": False, "reason": "%s 内容格式不对：%r" % (FAN_RPM_FILE, txt.strip()[:60])}
    try:
        rpm1, rpm2, temp, ts = int(f[0]), int(f[1]), float(f[2]), float(f[3])
    except ValueError:
        return {"available": False, "reason": "%s 内容无法解析：%r" % (FAN_RPM_FILE, txt.strip()[:60])}
    age = (snap_ts if snap_ts is not None else time.time()) - ts
    stale = age > stale_after
    out = {
        "available": True,
        "rpms": [rpm1, rpm2],
        "max_rpm": max(rpm1, rpm2),
        "package_c": temp,
        "age_s": age,
        "stale": stale,
        "source": FAN_RPM_FILE,
    }
    if stale:
        out["reason"] = "数据已过期 %.0f 秒（fan-rpmd 可能挂了）" % age
    return out


def parse_gpus():
    if not shutil.which("nvidia-smi"):
        return []
    fields = ("index,name,utilization.gpu,memory.used,memory.total,"
              "temperature.gpu,power.draw,power.limit,fan.speed")
    out = cmd(["nvidia-smi", "--query-gpu=" + fields, "--format=csv,noheader,nounits"], timeout=8)
    if not out:
        return []
    gpus = []
    for line in out.splitlines():
        f = [x.strip() for x in line.split(",")]
        if len(f) < 8:
            continue
        def num(x):
            try:
                return float(x)
            except ValueError:
                return None
        gpus.append(
            {
                "index": f[0],
                "name": f[1],
                "util_pct": num(f[2]),
                "mem_used": (num(f[3]) or 0) * 1024 * 1024,
                "mem_total": (num(f[4]) or 0) * 1024 * 1024,
                "celsius": num(f[5]),
                "power_w": num(f[6]),
                "power_limit_w": num(f[7]) if len(f) > 7 else None,
                "fan_pct": num(f[8]) if len(f) > 8 else None,
            }
        )
    return gpus


def parse_failed_units():
    out = cmd(["systemctl", "list-units", "--state=failed", "--no-legend", "--plain"],
              timeout=6)
    if not out:
        return []
    units = []
    for line in out.splitlines():
        f = line.split()
        if f:
            units.append(f[0])
    return units


def parse_failed_timers():
    out = cmd(["systemctl", "list-timers", "--failed", "--no-legend", "--plain"], timeout=6)
    if not out:
        return []
    return [line.split()[0] for line in out.splitlines() if line.split()]


def parse_mem_events():
    """内存压力的可靠计数器（不依赖日志文本，不会把自己数进去）。"""
    res = {"oom_kill_kernel": None, "pswpin": None, "pswpout": None, "cgroup_oom": None, "cgroup_max": None}
    txt = read_text("/proc/vmstat") or ""
    for line in txt.splitlines():
        f = line.split()
        if len(f) != 2:
            continue
        if f[0] in ("oom_kill", "pswpin", "pswpout"):
            try:
                res[f[0] if f[0] != "oom_kill" else "oom_kill_kernel"] = int(f[1])
            except ValueError:
                pass
    cg = read_text("/sys/fs/cgroup/memory.events")
    if cg:
        for line in cg.splitlines():
            f = line.split()
            if len(f) == 2 and f[0] in ("oom_kill", "oom", "max"):
                key = {"oom_kill": "cgroup_oom", "oom": "cgroup_oom", "max": "cgroup_max"}[f[0]]
                try:
                    res[key] = int(f[1])
                except ValueError:
                    pass
    return res


# 自己跑过的诊断命令会出现在日志里，且经常正好包含 "oom"/"error" 等字样，
# 计数时会把它们算成系统故障（自指噪声）。这里显式排除。
SELF_UNIT_MARKERS = ("dsh-subprocess", "dsysmon", "server-monitor")
BURST_WINDOW_MIN = 15


def _is_self_noise(unit):
    return any(m in (unit or "") for m in SELF_UNIT_MARKERS)


def parse_journal_events(hours=24):
    """内核侧信号只看 _TRANSPORT=kernel，彻底排除用户/工具产生的日志文本。"""
    res = {
        "oom": 0, "throttle": 0, "error_count": 0,
        "kernel_lines": 0, "unavailable": False, "errors_excluded_self": 0,
    }
    if not shutil.which("journalctl"):
        res["unavailable"] = True
        return res
    since = "-%dh" % int(hours)

    # 1) 内核日志：用 -k + -g 在 journalctl 侧过滤，避免拉全量
    out = cmd(["journalctl", "-k", "--since", since, "--no-pager", "-q",
               "-g", "oom|killed process|throttl|thermal"], timeout=20)
    if out is None:
        res["kernel_unavailable"] = True
    else:
        for line in out.splitlines():
            if not line.strip():
                continue
            res["kernel_lines"] += 1
            low = line.lower()
            if "oom" in low or "killed process" in low:
                res["oom"] += 1
            if "throttl" in low:
                res["throttle"] += 1

    # 2) 非内核错误日志：逐条取 _SYSTEMD_UNIT，剔除自己造成的
    out = cmd(["journalctl", "--since", since, "--no-pager", "-q", "-p", "err",
               "-o", "json", "--output-fields=_SYSTEMD_UNIT,SYSLOG_IDENTIFIER"], timeout=25)
    if out is None:
        res["errors_unavailable"] = True
    else:
        from collections import Counter
        by_unit = Counter()
        for line in out.splitlines():
            if not line.strip():
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                rec = {}
            unit = rec.get("_SYSTEMD_UNIT") or rec.get("SYSLOG_IDENTIFIER") or ""
            if _is_self_noise(unit):
                res["errors_excluded_self"] += 1
                continue
            by_unit[unit or "(未标注来源)"] += 1
        res["error_count"] = sum(by_unit.values())
        res["error_by_unit"] = by_unit.most_common(3)

    # 3) 近期错误"突发"检测。开机瞬间 ACPI/BIOS 噪声经常一次刷几十条，
    #    用 24h 总量判断会每次开机都误报；真正值得看的是持续在冒错。
    burst_since = "-%dmin" % BURST_WINDOW_MIN
    out = cmd(["journalctl", "--since", burst_since, "--no-pager", "-q", "-p", "err",
               "-o", "json", "--output-fields=_SYSTEMD_UNIT,SYSLOG_IDENTIFIER"], timeout=15)
    if out is not None:
        n = 0
        for line in out.splitlines():
            if not line.strip():
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                rec = {}
            if _is_self_noise(rec.get("_SYSTEMD_UNIT") or rec.get("SYSLOG_IDENTIFIER") or ""):
                continue
            n += 1
        res["error_burst"] = n
    return res


def parse_docker():
    if not shutil.which("docker"):
        return None
    out = cmd(["docker", "ps", "-a", "--format", "{{.State}}\t{{.Names}}\t{{.Image}}"], timeout=8)
    if out is None:
        return {"available": False, "running": 0, "total": 0, "items": []}
    items = []
    for line in out.splitlines():
        f = line.split("\t")
        if len(f) >= 3:
            items.append({"state": f[0], "name": f[1], "image": f[2]})
    return {
        "available": True,
        "running": sum(1 for i in items if i["state"] == "running"),
        "total": len(items),
        "items": items[:8],
    }


def _read_float(path):
    txt = read_text(path)
    if txt is None:
        return None
    try:
        return float(txt.strip())
    except ValueError:
        return None


def parse_adapter():
    """外接电源是否在线（决定"电池功率 0"该不该报警）。"""
    for name in sorted(os.listdir("/sys/class/power_supply")) if os.path.isdir("/sys/class/power_supply") else []:
        base = os.path.join("/sys/class/power_supply", name)
        if (read_text(os.path.join(base, "type")) or "").strip() not in ("Mains", "USB", "USB_CDP", "USB_DCP"):
            continue
        online = read_int(os.path.join(base, "online"))
        return {"name": name, "online": bool(online) if online is not None else None}
    return None


def parse_battery():
    """电池总电量与功率。

    energy_* 单位 µWh，power_now 单位 µW（有些机型只给 charge_* / current_now，
    这时用 voltage_now 换算）。插着电且充满时 power_now 恒为 0 —— 那是正常现象，
    不代表没在耗电，所以同时记录外接电源状态。
    """
    adapter = parse_adapter()
    result = None
    for bat in sorted(glob.glob("/sys/class/power_supply/BAT*")):
        g = lambda f: read_int(os.path.join(bat, f))
        gf = lambda f: _read_float(os.path.join(bat, f))
        volt = g("voltage_now")

        energy_now = g("energy_now")
        energy_full = g("energy_full")
        energy_design = g("energy_full_design")
        charge_now, charge_full, charge_design = g("charge_now"), g("charge_full"), g("charge_full_design")
        # 只给 charge_*（µAh）的机型：用电压换算成 µWh
        if energy_now is None and charge_now is not None and volt:
            energy_now = charge_now * volt / 1e6
        if energy_full is None and charge_full is not None and volt:
            energy_full = charge_full * volt / 1e6
        if energy_design is None and charge_design is not None and volt:
            energy_design = charge_design * volt / 1e6

        power = gf("power_now")
        if not power:
            cur = gf("current_now")
            if cur and volt:
                power = abs(cur) * volt / 1e6             # µA × µV -> µW

        health = (100.0 * energy_full / energy_design) if (energy_full and energy_design) else None
        result = {
            "name": os.path.basename(bat),
            "model": (read_text(os.path.join(bat, "model_name")) or "").strip() or None,
            "manufacturer": (read_text(os.path.join(bat, "manufacturer")) or "").strip() or None,
            "capacity": g("capacity"),
            "capacity_level": (read_text(os.path.join(bat, "capacity_level")) or "").strip() or None,
            "status": (read_text(os.path.join(bat, "status")) or "").strip() or None,
            "energy_now_uwh": energy_now,
            "energy_full_uwh": energy_full,
            "energy_design_uwh": energy_design,
            "power_uw": power,
            "voltage_uv": volt,
            "cycle_count": g("cycle_count"),
            "health_pct": health,
            "adapter": adapter,
        }
        break
    return {"battery": result, "adapter": adapter}


BATTERY_STATE_DEFAULT = os.path.join(STATE_DIR, "battery-state.json")


def track_battery_energy(bat, path):
    """跨次运行累计电池吞吐。

    单次快照无法知道"已经用了多少电"——它只是电量读数。要得到累计值必须把
    上一次的读数和时间点存下来做差。只在同一次开机内累计，避免休眠/重启把
    巨大时间差当成用电。
    """
    info = {"available": False, "discharged_wh": None, "charged_wh": None,
            "tracked_hours": None, "samples": None, "avg_discharge_w": None,
            "since": None, "note": None}
    e_now = bat.get("energy_now_uwh")
    if e_now is None:
        info["note"] = "电池不报告 energy_now，无法累计"
        return info

    prev = None
    txt = read_text(path)
    if txt:
        try:
            cand = json.loads(txt)
            if isinstance(cand, dict):
                prev = cand
        except ValueError:
            prev = None

    boot = (read_text("/proc/sys/kernel/random/boot_id") or "").strip()
    now = time.time()
    st = {"boot_id": boot, "ts": now, "energy_uwh": e_now,
          "discharged_uwh": 0.0, "charged_uwh": 0.0, "duration_s": 0.0,
          "samples": 0, "peak_power_uw": bat.get("power_uw") or 0}

    if prev and prev.get("boot_id") == boot and isinstance(prev.get("energy_uwh"), (int, float)):
        dt = now - float(prev.get("ts") or now)
        dwh = e_now - float(prev["energy_uwh"])
        carry = {k: prev.get(k, st[k]) for k in
                 ("discharged_uwh", "charged_uwh", "duration_s", "samples", "peak_power_uw")}
        if dt < 5 and not dwh:
            # 短时间内的重复调用：电量本来就没变，不该算作一次采样，
            # 否则"跟踪窗口"会虚涨而实际什么都没测到
            st.update(carry)
            st["ts"] = prev.get("ts", now)
            info["note"] = "距上次调用不足 5 秒且电量未变，本次不累计"
            _write_state(path, st)
            return _battery_track_result(info, st)
        if dt > 7200:
            # 间隔过长（休眠 / 挂起 / 很久没调用）：这一段电量差不可信，不计入，
            # 但必须保留已累计的历史，否则合盖一觉就把记录清零了。
            st.update(carry)
            info["note"] = "距上次调用超过 2 小时，这一段未计入累计"
        else:
            st["discharged_uwh"] = float(prev.get("discharged_uwh") or 0) + max(0.0, -dwh)
            st["charged_uwh"] = float(prev.get("charged_uwh") or 0) + max(0.0, dwh)
            st["duration_s"] = float(prev.get("duration_s") or 0) + dt
            st["samples"] = int(prev.get("samples") or 0) + 1
            st["peak_power_uw"] = max(float(prev.get("peak_power_uw") or 0), bat.get("power_uw") or 0)
    elif prev and prev.get("boot_id") != boot:
        info["note"] = "重启后重新开始累计"

    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".bat-")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(st, fh)
        os.replace(tmp, path)
    except OSError as e:
        info["note"] = "状态文件写入失败：%s" % e
    return _battery_track_result(info, st)


def _write_state(path, st):
    try:
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(st, fh)
    except OSError:
        pass


def _battery_track_result(info, st):
    info["available"] = True
    info["discharged_wh"] = st["discharged_uwh"] / 1e6
    info["charged_wh"] = st["charged_uwh"] / 1e6
    info["tracked_hours"] = st["duration_s"] / 3600.0
    info["samples"] = st["samples"]
    info["peak_power_w"] = st["peak_power_uw"] / 1e6
    if st["duration_s"] > 0:
        info["avg_discharge_w"] = st["discharged_uwh"] / 1e6 / (st["duration_s"] / 3600.0)
    info["since"] = datetime.fromtimestamp(st["ts"]).strftime("%m-%d %H:%M")
    return info


def primary_ip():
    vals = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("1.1.1.1", 53))
        vals.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    txt = cmd(["hostname", "-I"], timeout=3) or ""
    for ip in txt.split():
        if ip not in vals:
            vals.append(ip)
    return vals[:3]


# ---------------------------------------------------------------- 评分

DEFAULTS = {
    "mem_warn": 85.0,
    "mem_crit": 95.0,
    "swap_warn": 50.0,
    "swap_crit": 85.0,
    # 换页速率（页/秒）：swap 占用高不等于正在颠簸 —— 关键看有没有持续换入换出。
    # 这条是"内存真的不够用"的直接信号，比占用率更能说明问题。
    "swap_io_warn": 200.0,
    "swap_io_crit": 2000.0,
    "disk_warn": 85.0,
    "disk_crit": 95.0,
    "inode_warn": 85.0,
    "inode_crit": 95.0,
    "coretemp_warn": 80.0,
    "coretemp_crit": 90.0,
    "gpu_warn": 80.0,
    "gpu_crit": 90.0,
    "load_warn": 1.0,   # × vCPU 数
    "load_crit": 2.0,
    "top_cpu_warn": 90.0,
    "top_mem_warn": 30.0,
    "journal_err_warn": 150,   # 24h 总量：开机 BIOS/ACPI 噪声一次性就能刷上百条
    "journal_err_crit": 600,
    "burst_warn": 10,           # 近 15min 增量：突发才说明有东西在持续失败
    "burst_crit": 50,
}

LEVEL_ORDER = {"ok": 0, "info": 1, "warn": 2, "crit": 3}

# 优先级：越靠前越重要，--alert-at 用它选取要上报的条目
LEVEL_PRIORITY = {
    "journal_errors": 100,
    "oom": 95,
    "throttle": 90,
    "disk": 85,
    "inode": 84,
    "mem": 80,
    "swap": 78,
    "swap_io": 79,
    "coretemp": 70,
    "gpu": 68,
    "top_mem": 60,
    "top_cpu": 55,
    "failed_units": 50,
    "failed_timers": 45,
    "battery": 42,
    "load": 40,
    "docker_down": 20,
}


def _lvl(pct, warn, crit):
    if pct >= crit:
        return "crit"
    if pct >= warn:
        return "warn"
    return "ok"


def _is_discharging(bat):
    return (bat.get("status") or "").lower().startswith("discharg")


def battery_level(bat):
    """电池只看"拔电后在放电且电量低"，插电时电量低不算问题。"""
    if not bat or bat.get("capacity") is None:
        return "ok"
    if not _is_discharging(bat):
        return "ok"
    cap = bat["capacity"]
    if cap < 10:
        return "crit"
    if cap < 25:
        return "warn"
    return "ok"


def evaluate(snap, th):
    """把原始数据折算成 条目 -> 等级 的评分，供面板和退出码使用。"""
    levels = {}
    levels["mem"] = _lvl(snap["mem"]["used_pct"], th.mem_warn, th.mem_crit)
    rate = snap["mem"].get("swap_pages_per_s")
    if rate is not None:
        levels["swap_io"] = _lvl(rate, th.swap_io_warn, th.swap_io_crit)
    if snap["mem"]["swap_total"]:
        levels["swap"] = _lvl(snap["mem"]["swap_pct"], th.swap_warn, th.swap_crit)
    worst_disk = ("ok", None)
    for m in snap["disks"]["mounts"]:
        lv = _lvl(m["used_pct"], th.disk_warn, th.disk_crit)
        if LEVEL_ORDER[lv] > LEVEL_ORDER[worst_disk[0]]:
            worst_disk = (lv, m["mount"])
    levels["disk"] = worst_disk[0]

    worst_ino = ("ok", None)
    for m in snap["disks"]["mounts"]:
        if m.get("inode_pct") is not None:
            lv = _lvl(m["inode_pct"], th.inode_warn, th.inode_crit)
            if LEVEL_ORDER[lv] > LEVEL_ORDER[worst_ino[0]]:
                worst_ino = (lv, m["mount"])
    levels["inode"] = worst_ino[0]

    cpu_pct = snap["cpu"]["used_pct"]
    ncpu = snap["cpu"]["count"] or 1
    if cpu_pct is not None:
        levels["load"] = _lvl(snap["load"][0] / ncpu * 100.0, th.load_warn * 100, th.load_crit * 100)

    for t in snap["temps"]:
        low = (t.get("label") or "").lower()
        chip = (t.get("chip") or "").lower()
        if "coretemp" in chip or "package" in low or "x86_pkg" in chip or "cpu" in low:
            levels["coretemp"] = _lvl(t["celsius"], th.coretemp_warn, th.coretemp_crit)
            break

    for g in snap["gpus"]:
        if g.get("celsius") is not None:
            levels["gpu"] = _lvl(g["celsius"], th.gpu_warn, th.gpu_crit)
            break

    if snap["procs"]["top_cpu"]:
        levels["top_cpu"] = _lvl(snap["procs"]["top_cpu"][0]["pcpu"], th.top_cpu_warn, 100.0)
    if snap["procs"]["top_mem"]:
        levels["top_mem"] = _lvl(snap["procs"]["top_mem"][0]["pmem"], th.top_mem_warn, 100.0)

    je = snap["journal"]
    burst = je.get("error_burst")
    if burst is not None:
        # 近期突发比累计总量更能说明问题
        levels["journal_errors"] = _lvl(burst, th.burst_warn, th.burst_crit)
    elif je.get("unavailable"):
        levels["journal_errors"] = "info"
    else:
        levels["journal_errors"] = _lvl(je["error_count"], th.journal_err_warn, th.journal_err_crit)
    levels["oom"] = "crit" if je["oom"] else "ok"
    levels["throttle"] = "warn" if je["throttle"] else "ok"

    levels["failed_units"] = "crit" if snap["failed_units"] else "ok"
    levels["failed_timers"] = "warn" if snap["failed_timers"] else "ok"
    levels["battery"] = battery_level(snap.get("host", {}).get("battery"))

    dock = snap["docker"]
    if dock and dock.get("available") and dock["total"] and dock["running"] < dock["total"]:
        levels["docker_down"] = "warn"
    elif dock is not None:
        levels["docker_down"] = "ok"
    return levels


# ---------------------------------------------------------------- 渲染

C = {
    "reset": "\033[0m", "bold": "\033[1m", "dim": "\033[2m",
    "red": "\033[31m", "green": "\033[32m", "yellow": "\033[33m",
    "blue": "\033[34m", "cyan": "\033[36m", "magenta": "\033[35m",
}
LEVEL_COLOR = {"ok": "green", "info": "cyan", "warn": "yellow", "crit": "red"}
LEVEL_TAG = {"ok": "OK", "info": "·", "warn": "WARN", "crit": "CRIT"}


class Renderer:
    def __init__(self, use_color=True):
        self.use_color = use_color and sys.stdout.isatty()

    def c(self, text, *styles):
        if not self.use_color or not styles:
            return text
        pre = "".join(C.get(s, "") for s in styles)
        return pre + text + C["reset"]

    def lvl(self, level):
        return self.c(LEVEL_TAG[level].rjust(4), LEVEL_COLOR[level], "bold" if level == "crit" else "dim")


def human_bytes(n, digits=1):
    if n is None:
        return "-"
    n = float(n)
    for unit in ("B", "K", "M", "G", "T", "P"):
        if abs(n) < 1024 or unit == "P":
            return ("%%.%df%s" % (digits, unit)) % n if unit != "B" else "%d B" % n
        n /= 1024.0


def human_rate(bps):
    return human_bytes(bps, 1) + "/s"


def human_dur(seconds):
    if seconds is None:
        return "-"
    seconds = int(seconds)
    d, rem = divmod(seconds, 86400)
    h, rem = divmod(rem, 3600)
    m, _ = divmod(rem, 60)
    if d:
        return "%dd %dh %dm" % (d, h, m)
    if h:
        return "%dh %dm" % (h, m)
    return "%dm" % m


def bar(pct, width=20):
    pct = max(0.0, min(100.0, float(pct or 0)))
    filled = int(round(width * pct / 100.0))
    color = "green" if pct < 70 else ("yellow" if pct < 88 else "red")
    return (R.c("█" * filled, color) + R.c("░" * (width - filled), "dim"))


R = Renderer()  # 占位，main() 里按参数重建


def fmt_power(uw):
    """µW -> 人类可读功率。"""
    if uw is None:
        return "-"
    w = uw / 1e6
    if abs(w) >= 1:
        return "%.1f W" % w
    if abs(w) >= 0.001:
        return "%.0f mW" % (w * 1000)
    return "0 W"


def fmt_wh(uwh):
    """µWh -> Wh。"""
    if uwh is None:
        return "-"
    return "%.1f Wh" % (uwh / 1e6)


def render_battery(bat, energy, adapter):
    """总电量（当前 / 满电 / 设计容量）与电池功率（放电功率 / 充电功率 / 累计）。"""
    cap = bat.get("capacity")
    lvl = battery_level(bat)
    discharging = _is_discharging(bat)
    charging = (bat.get("status") or "").lower().startswith("charg")

    if cap is not None:
        energy_txt = ""
        if bat.get("energy_now_uwh") is not None and bat.get("energy_full_uwh"):
            energy_txt = "当前 %.1f Wh / 满电 %.1f Wh" % (
                bat["energy_now_uwh"] / 1e6, bat["energy_full_uwh"] / 1e6)
        kv("电量", "%s %s" % (bar(cap), fmt_pct(cap)), lvl, energy_txt)

    health = bat.get("health_pct")
    if health is not None:
        h_lvl = "warn" if health < 60 else "ok"
        kv("电池健康", "%.0f%%" % health, h_lvl,
           "满电 %.1f Wh / 设计 %.1f Wh" % (bat["energy_full_uwh"] / 1e6, bat["energy_design_uwh"] / 1e6))
    if bat.get("cycle_count") is not None:
        c = bat["cycle_count"]
        kv("循环次数", "%d 次" % c, "warn" if c > 800 else "ok",
           "锂电一般 300–1000 次后容量明显衰减")

    # —— 功率
    p_uw = bat.get("power_uw")
    suffix = ""
    if not p_uw and discharging and energy and energy.get("avg_discharge_w"):
        p_uw = energy["avg_discharge_w"] * 1e6
        suffix = "（由两次采样差值推算）"
    if p_uw:
        if charging:
            note = "充电功率" + suffix
        elif discharging:
            note = "放电功率" + suffix
        else:
            note = "电池净功率" + suffix
    elif adapter and adapter.get("online"):
        note = "插着电且已充满，电池不吞吐，功率读数恒为 0"
    elif adapter:
        note = "电池未吞吐（若刚拔电，等几次刷新再读）"
    else:
        note = "系统未报告电流/功率"
    kv("电池功率", fmt_power(p_uw), "ok" if (p_uw or adapter) else "info", note)

    # —— 剩余可用时间（仅放电时才有意义）
    if discharging and p_uw and bat.get("energy_now_uwh"):
        hours = (bat["energy_now_uwh"] / 1e6) / (p_uw / 1e6)
        kv("预计可用", "%d 小时 %d 分" % (int(hours), int(hours % 1 * 60)), "ok",
           "按当前功率线性估算，实际随负载变化")

    # —— 外接电源
    if adapter:
        online = adapter.get("online")
        kv("外接电源", ("已接通（%s）" % adapter["name"]) if online else ("未接（%s）" % adapter["name"]), "ok")

    # —— 累计吞吐（跨次运行跟踪得出）
    if energy and energy.get("available"):
        disp = "累计放电 %.2f Wh · 累计充入 %.2f Wh" % (energy["discharged_wh"], energy["charged_wh"])
        note = "跟踪窗口 %.1f 小时 / %d 次采样 · 自 %s 起" % (
            energy["tracked_hours"], energy["samples"], energy["since"])
        if energy.get("avg_discharge_w"):
            note += " · 平均放电 %.1f W" % energy["avg_discharge_w"]
        if energy.get("peak_power_w"):
            note += " · 峰值 %.1f W" % energy["peak_power_w"]
        kv("累计电量", disp, "ok", note)
        print("  %-18s %s" % ("", R.c("累计值只统计本工具被调用期间的电量变化，"
                                       "不会后台自己记；刚重启或采样间隔 >2 小时的那一段不计入", "dim")))
        if energy.get("note"):
            print("  %-18s %s" % ("", R.c(energy["note"], "dim")))
    elif bat and energy is not None:
        print("  %-18s %s" % ("", R.c("累计电量不可用：" + (energy.get("note") or "未知原因"), "dim")))


def section(title):
    print()
    R_ = R
    print(R_.c("── " + title + " " + "─" * max(0, 46 - len(title) * 2), "cyan", "bold"))


def kv(label, value, level=None, note=""):
    tag = R.lvl(level) + " " if level else "     "
    line = "  %s%-13s %s" % (tag, label, value)
    if note:
        line += R.c("  " + note, "dim")
    print(line)


def table(headers, rows, widths):
    print("  " + "".join(R.c(h.ljust(w), "bold") for h, w in zip(headers, widths)))
    for row in rows:
        cells = []
        for i, (cell, w) in enumerate(zip(row, widths)):
            s = str(cell)
            if len(s) > w:
                s = s[: w - 1] + "…"
            cells.append(s.ljust(w))
        print("  " + "".join(cells))
    return


# ---------------------------------------------------------------- 采集总入口


class Thresholds:
    pass


def collect(args):
    ts_now = time.time()
    interval = max(0.1, min(30.0, args.interval))
    a_cpu = parse_cpu_times()
    a_net = parse_netdev()
    a_proc = parse_proc_times()
    mem = parse_meminfo()
    a_swap = parse_mem_events()
    t0 = time.time()
    time.sleep(interval)
    b_cpu = parse_cpu_times()
    b_net = parse_netdev()
    b_proc = parse_proc_times()
    b_swap = parse_mem_events()
    dt = time.time() - t0

    # 换页速率：占用率高不等于在颠簸，真正伤性能的是"持续换入换出"。
    # 用同一个采样窗口的前后差值算，和 CPU / 网速同一套口径。
    def _swap_rate(key):
        a, b = a_swap.get(key), b_swap.get(key)
        if a is None or b is None or dt <= 0 or b < a:
            return None
        return (b - a) / dt
    rin, rout = _swap_rate("pswpin"), _swap_rate("pswpout")
    if rin is not None and rout is not None:
        mem["swap_pages_per_s"] = rin + rout
        mem["swap_in_per_s"] = rin
        mem["swap_out_per_s"] = rout

    cpu = cpu_delta(a_cpu, b_cpu) or {"total": None, "cores": []}
    la = parse_loadavg()
    journal = parse_journal_events(args.journal_hours)
    mem_events = parse_mem_events()
    power = parse_battery()
    bat = power.get("battery")
    bat_energy = None
    if bat:
        path = (args.battery_state if getattr(args, "battery_state", None) else BATTERY_STATE_DEFAULT)
        if not getattr(args, "no_battery_track", False):
            bat_energy = track_battery_energy(bat, path)
    # 内核计数器比日志文本可靠：oom_kill 是真正的内核 OOM 击杀次数
    if mem_events.get("oom_kill_kernel"):
        journal["oom"] = max(journal["oom"], mem_events["oom_kill_kernel"])
    if mem_events.get("cgroup_oom"):
        journal["cgroup_oom"] = mem_events["cgroup_oom"]
    journal["swap_in_pages"] = mem_events.get("pswpin")
    journal["swap_out_pages"] = mem_events.get("pswpout")

    snap = {
        "ts": time.time(),
        "iso": datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %z"),
        "host": {
            "hostname": socket.gethostname(),
            "os": parse_os_release(),
            "kernel": os.uname().release,
            "arch": os.uname().machine,
            "boot": (read_text("/proc/sys/kernel/random/boot_id") or "").strip(),
            "virt": detect_virt(),
            "uptime_s": parse_uptime(),
            "ips": primary_ip(),
            "battery": bat,
            "adapter": power.get("adapter"),
            "battery_energy": bat_energy,
        },
        "cpu": {
            "count": len(a_cpu["cores"]) if a_cpu else (os.cpu_count() or 0),
            "used_pct": cpu["total"],
            "cores_pct": cpu["cores"],
            "model": cpu_model(),
            "gov": (read_text("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") or "").strip() or None,
            "mhz": current_mhz(),
            "sample_s": round(dt, 3),
        },
        "load": la,
        "mem": mem,
        "mem_events": mem_events,
        "disks": parse_disks(all_mounts=getattr(args, "report", False)),
        "net": {"rates": net_delta(a_net, b_net, dt), "counters": b_net, "sockets": parse_sockets()},
        "procs": parse_procs(args.top, a_proc, b_proc, dt, mem["total"]),
        "temps": parse_temps(),
        "fans": parse_fans(snap_ts=ts_now),
        "gpus": parse_gpus(),
        "failed_units": parse_failed_units(),
        "failed_timers": parse_failed_timers() if getattr(args, "check_timers", False) else [],
        "journal": journal,
        "docker": parse_docker(),
    }
    if getattr(args, "disk_detail", False):
        fill_root_scan(snap["disks"])
    levels = evaluate(snap, args.th)
    snap["levels"] = levels
    return snap


def detect_virt():
    txt = cmd(["systemd-detect-virt"], timeout=3)
    if txt and txt.strip() != "none":
        return txt.strip()
    return None


def cpu_model():
    txt = read_text("/proc/cpuinfo") or ""
    for line in txt.splitlines():
        if line.lower().startswith("model name"):
            return line.split(":", 1)[1].strip()
    return None


def current_mhz():
    vals = []
    for p in glob.glob("/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq"):
        v = read_int(p)
        if v:
            vals.append(v / 1000.0)
    if not vals:
        return None
    return (min(vals), max(vals), sum(vals) / len(vals))


# ---------------------------------------------------------------- 面板输出


def render(snap, args, refresh_note=""):
    R.use_color = (not args.no_color) and sys.stdout.isatty()
    host, cpu = snap["host"], snap["cpu"]
    mem, disks, net = snap["mem"], snap["disks"], snap["net"]
    lv = snap["levels"]

    head = "%s  %s" % (R.c("dsysmon", "bold", "magenta"), R.c(snap["iso"], "dim"))
    if refresh_note:
        head += R.c("  " + refresh_note, "dim")
    print(head)
    print("  " + R.c("%s · %s %s · %s · up %s · %d vCPU · load %s %s %s" % (
        host["hostname"], host["os"], host["kernel"], host["arch"],
        human_dur(host["uptime_s"]), cpu["count"],
        fmt_num(snap["load"][0]), fmt_num(snap["load"][1]), fmt_num(snap["load"][2])), "dim"))
    extra = []
    if host["ips"]:
        extra.append("IP " + ", ".join(host["ips"]))
    if host["virt"]:
        extra.append("虚拟化 " + host["virt"])
    bat = host.get("battery")
    if bat:
        head_txt = "电池 %s%%" % bat["capacity"] if bat.get("capacity") is not None else "电池"
        if bat.get("status"):
            head_txt += " " + bat["status"]
        if bat.get("power_uw") is not None:
            head_txt += " · %s" % fmt_power(bat["power_uw"])
        elif bat.get("adapter") and bat["adapter"].get("online"):
            head_txt += " · 外接供电"
        extra.append(head_txt)
    if extra:
        print("  " + R.c(" · ".join(extra), "dim"))

    # ---- 电池 / 电源
    if bat:
        section("电池 / 电源")
        render_battery(bat, host.get("battery_energy"), host.get("adapter"))

    # ---- CPU
    section("CPU")
    pct = cpu["used_pct"]
    kv("使用率", "%s %s" % (bar(pct), fmt_pct(pct)), lv.get("load"))
    if cpu["model"]:
        mhz = cpu["mhz"]
        note = ""
        if mhz:
            note = "频率 %.0f–%.0f MHz（均 %.0f）" % (mhz[0], mhz[1], mhz[2])
        if cpu["gov"]:
            note += (" · " if note else "") + "governor " + cpu["gov"]
        print("  %-13s %s" % ("型号", R.c(shorten(cpu["model"], 62), "dim")))
        if note:
            print("  %-13s %s" % ("", R.c(note, "dim")))
    cores = cpu["cores_pct"]
    if cores:
        per = 4
        for i in range(0, len(cores), per):
            chunk = cores[i:i + per]
            cells = []
            for j, c in enumerate(chunk):
                color = "green" if c < 70 else ("yellow" if c < 88 else "red")
                cells.append(R.c("cpu%-2d %5.1f%%" % (i + j, c), color))
            print("  %-13s %s" % ("", "  ".join(cells)))
    print("  %-13s %s" % ("负载", "1m %s / 5m %s / 15m %s   (%.1f vCPU)" % (
        fmt_num(snap["load"][0]), fmt_num(snap["load"][1]), fmt_num(snap["load"][2]), cpu["count"])))
    if snap["procs"]["count"] is not None:
        print("  %-13s %d" % ("运行进程", snap["procs"]["count"]))

    # ---- 内存
    section("内存 / 交换")
    kv("物理内存", "%s %s" % (bar(mem["used_pct"]), fmt_pct(mem["used_pct"])), lv.get("mem"),
       "%s / %s（可用 %s）" % (human_bytes(mem["used"]), human_bytes(mem["total"]), human_bytes(mem["available"])))
    if mem["swap_total"]:
        kv("交换分区", "%s %s" % (bar(mem["swap_pct"]), fmt_pct(mem["swap_pct"])), lv.get("swap"),
           "%s / %s" % (human_bytes(mem["swap_used"]), human_bytes(mem["swap_total"])))
    else:
        kv("交换分区", "未启用", "ok")
    print("  %-13s %s" % ("缓存/缓冲", R.c("%s cached · %s buffers · %s dirty" % (
        human_bytes(mem["cached"]), human_bytes(mem["buffers"]), human_bytes(mem["dirty"])), "dim")))
    if mem.get("swap_pages_per_s") is not None:
        # 注意别把上面那个 lv（levels 字典）覆盖掉 —— 踩过，会在后面的告警段炸掉
        io_level = lv.get("swap_io", "ok")
        rate = mem["swap_pages_per_s"]
        print("  %s %-13s %s %s" % (
            R.lvl(io_level), "换页速率",
            R.c("%.0f 页/秒" % rate, LEVEL_COLOR[io_level], "bold"),
            R.c("换入 %.0f / 换出 %.0f 页/秒%s" % (
                mem.get("swap_in_per_s", 0), mem.get("swap_out_per_s", 0),
                "（持续颠簸会明显拖慢整机）" if io_level != "ok" else ""), "dim")))

    # ---- 磁盘
    section("磁盘")
    rows = []
    for m in disks["mounts"]:
        lvl = _lvl(m["used_pct"], args.th.disk_warn, args.th.disk_crit)
        color = LEVEL_COLOR[lvl]
        rows.append([
            m["mount"],
            "%.1f%%" % m["used_pct"],
            "%s / %s" % (human_bytes(m["used"]), human_bytes(m["size"])),
            human_bytes(m["avail"]),
            ("%.0f%%" % m["inode_pct"]) if m.get("inode_pct") is not None else "-",
            shorten(os.path.basename(m["device"]), 16),
        ])
    table(["挂载点", "用量", "已用", "可用", "inode", "设备"], rows, [16, 7, 16, 10, 7, 16])
    for m in disks["mounts"]:
        lvl = _lvl(m["used_pct"], args.th.disk_warn, args.th.disk_crit)
        if lvl != "ok":
            print("  " + R.lvl(lvl) + R.c(" %s 使用率 %.1f%%（阈值 %.0f%%）" % (m["mount"], m["used_pct"], args.th.disk_warn), "bold"))
    io = disks.get("io") or {}
    if io:
        print("  %-13s %s" % ("块设备累计", R.c("  ".join(
            "%s R%s/W%s" % (n, human_bytes(v["read_bytes"], 0), human_bytes(v["write_bytes"], 0))
            for n, v in sorted(io.items())[:5]), "dim")))
    if args.disk_detail and disks.get("root_scan"):
        scan = disks["root_scan"]
        section("根目录占用 Top（du -x -d1 /）")
        if scan["error"]:
            kv("扫描", scan["error"], "warn")
        for e in scan["entries"]:
            print("  %-13s %s" % (human_bytes(e["bytes"], 1), e["path"]))

    # ---- 网络
    section("网络")
    counters = net.get("counters") or {}
    if counters:
        print("  %-13s %s" % ("累计流量", R.c("  ".join(
            "%s ↓%s ↑%s" % (n, human_bytes(c[0], 1), human_bytes(c[1], 1))
            for n, c in sorted(counters.items())), "dim")))
    rates = net.get("rates") or {}
    if rates:
        rows = [[n, human_rate(v[0]), human_rate(v[1])] for n, v in sorted(rates.items())]
        table(["接口", "下行", "上行"], rows, [16, 14, 14])
    else:
        kv("速率", "无活动接口或采样间隔过短", "info")
    sock = net.get("sockets") or {}
    by = sock.get("by_state") or {}
    print("  %-13s %s" % ("TCP 连接", R.c("总计 %d（ESTABLISHED %d / TIME_WAIT %d / LISTEN %d）" % (
        sock.get("total", 0), by.get("ESTABLISHED", 0), by.get("TIME_WAIT", 0), by.get("LISTEN", 0)), "dim")))
    if by:
        print("  %-13s %s" % ("", R.c("  ".join("%s=%d" % (k, v) for k, v in sorted(by.items())), "dim")))
    ports = sock.get("listening_ports") or []
    if ports:
        print("  %-13s %s" % ("监听端口", shorten(", ".join(str(p) for p in ports), 90)))
    if sock.get("established_ports"):
        print("  %-13s %s" % ("外部连接端口", R.c(", ".join(str(p) for p in sorted(set(sock["established_ports"]))[:14]), "dim")))

    # ---- 温度 / GPU
    if snap["temps"] or snap["gpus"]:
        section("温度 / GPU")

    def temp_level(t):
        low = (t.get("label") or "").lower()
        chip = (t.get("chip") or "").lower()
        if "coretemp" in chip or "package" in low or "x86_pkg" in chip:
            return _lvl(t["celsius"], args.th.coretemp_warn, args.th.coretemp_crit)
        if t.get("crit") and t["celsius"] >= t["crit"] - 5:
            return "warn"
        return "ok"

    def temp_row(t):
        lvl = temp_level(t)
        chip = t["chip"].replace("-virtual-0", "").replace("-isa-0000", "").replace("-pci-", "/")
        note = ("临界 %.0f°C" % t["crit"]) if t.get("crit") else ""
        print("  %s %-15s %s  %s" % (
            R.lvl(lvl), R.c(chip, "dim"),
            R.c("%6.1f°C" % t["celsius"], LEVEL_COLOR[lvl], "bold"),
            R.c(("%s · %s" % (t["label"], note)).strip(" ·"), "dim")))

    # 一切正常时压成两行摘要，不逐条刷屏；有异常或 --report 才展开明细
    problems = [t for t in snap["temps"] if temp_level(t) != "ok"]
    if snap["temps"] and not problems and not args.report:
        ordered = sorted(snap["temps"], key=lambda t: -t["celsius"])
        head = ordered[0]
        print("  %s %s  %s" % (
            R.lvl("ok"), R.c("%5.1f°C" % head["celsius"], "green", "bold"),
            R.c("最高：%s · 共 %d 个传感器均在阈值内" % (
                shorten(head["chip"] + " " + head["label"], 50), len(snap["temps"])), "dim")))
    else:
        for t in snap["temps"]:
            temp_row(t)

    for g in snap["gpus"]:
        lvl = "ok"
        if g.get("celsius") is not None:
            lvl = _lvl(g["celsius"], args.th.gpu_warn, args.th.gpu_crit)
        mpct = 100.0 * g["mem_used"] / g["mem_total"] if g["mem_total"] else 0.0
        print("  %s %-15s %s" % (R.lvl(lvl), "GPU " + g["index"],
                                 R.c("%.0f°C" % (g["celsius"] or 0), LEVEL_COLOR[lvl], "bold") +
                                 "  SM %.0f%%  显存 %s/%s (%.0f%%)  %.1fW" % (
                                     g["util_pct"] or 0, human_bytes(g["mem_used"], 1),
                                     human_bytes(g["mem_total"], 1), mpct, g["power_w"] or 0)))
        print("  %-17s %s" % ("", R.c(shorten(g["name"], 60), "dim")))

    # ---- 进程
    section("占用最高的进程")
    ncpu_total = max(1, snap["cpu"]["count"] or 1)
    print("  " + R.c("（整机%% = 该进程占全部 %d 个核的比例；单核占满 = 整机 %.1f%%）"
                     % (ncpu_total, 100.0 / ncpu_total), "dim"))
    # 进程 CPU 口径与盘面保持一致：给"整机占比"，另附折合核数。
    # 单核占满 = 100%/核数（例如 8 核时 1 核满载 = 整机 12.5%）。
    ncore = max(1, snap["cpu"]["count"] or 1)
    rows = []
    for p in snap["procs"]["top_cpu"]:
        rows.append([str(p["pid"]), p["comm"],
                     "%.1f%%" % (p["pcpu"] / ncore),
                     "%.2f 核" % (p["pcpu"] / 100.0),
                     human_bytes(p["rss"], 1), shorten(p["args"], 40)])
    table(["PID", "名称", "整机%", "折合核", "RSS", "命令"], rows, [8, 15, 8, 8, 9, 40])
    rows = []
    for p in snap["procs"]["top_mem"]:
        rows.append([str(p["pid"]), p["comm"], "%.1f%%" % p["pmem"], human_bytes(p["rss"], 1), shorten(p["args"], 48)])
    print()
    table(["PID", "名称", "MEM%", "RSS", "命令（按内存）"], rows, [8, 16, 7, 9, 48])

    # ---- 服务 / 日志
    section("服务与日志")
    je = snap["journal"]
    oom_n = je.get("oom", 0)
    kv("内核 OOM 击杀", "%d 次" % oom_n, "crit" if oom_n else "ok",
       "journalctl -k -g oom 复查" if oom_n else "内核未杀过进程")
    if je.get("cgroup_oom"):
        kv("cgroup OOM", "%d 次" % je["cgroup_oom"], "crit")
    kv("近 %dh 降频/过热" % args.journal_hours, "%d 条" % je.get("throttle", 0),
       "warn" if je.get("throttle") else "ok")
    if je.get("kernel_unavailable"):
        kv("内核日志", "不可读（权限或无 journal）", "info")
    if je.get("errors_unavailable"):
        kv("近 %dh 错误日志" % args.journal_hours, "journalctl 不可用", "info")
    else:
        burst = je.get("error_burst")
        if burst is None:
            kv("近 %dh 错误日志" % args.journal_hours, "%d 条" % je.get("error_count", 0),
               _lvl(je.get("error_count", 0), args.th.journal_err_warn, args.th.journal_err_crit))
        else:
            # 突发优先：开机瞬间的 BIOS/ACPI 噪声不该长期占着告警位
            kv("错误日志突发", "近 %d 分钟 %d 条" % (BURST_WINDOW_MIN, burst),
               _lvl(burst, args.th.burst_warn, args.th.burst_crit),
               "近 %dh 累计 %d 条" % (args.journal_hours, je.get("error_count", 0)))
        if je.get("error_by_unit"):
            print("  %-18s %s" % ("", R.c("主要来源：" + " · ".join(
                "%s ×%d" % (u, n) for u, n in je["error_by_unit"]), "dim")))
    if je.get("errors_excluded_self"):
        print("  %-18s %s" % ("", R.c("已排除 %d 条本机工具自身产生的日志" % je["errors_excluded_self"], "dim")))
    me = snap.get("mem_events") or {}
    if me.get("pswpin") is not None:
        print("  %-18s %s" % ("换页累计", R.c("换入 %s · 换出 %s（开机以来）" % (
            human_bytes(me["pswpin"] * 4096, 1), human_bytes(me["pswpout"] * 4096, 1)), "dim")))
    if snap["failed_units"]:
        for u in snap["failed_units"]:
            kv("失败单元", u, "crit")
        print("  %-18s %s" % ("", R.c("查看：systemctl status " + snap["failed_units"][0], "dim")))
    else:
        kv("失败单元", "无", "ok")
    if snap["failed_timers"]:
        for u in snap["failed_timers"]:
            kv("失败定时器", u, "warn")
    dock = snap["docker"]
    if dock is not None:
        if not dock.get("available"):
            kv("Docker", "守护进程不可达", "info")
        else:
            kv("Docker", "%d 运行 / %d 容器" % (dock["running"], dock["total"]),
               "warn" if dock["total"] and dock["running"] < dock["total"] else "ok")
            stopped = [i["name"] for i in dock["items"] if i["state"] != "running"]
            if stopped:
                print("  %-18s %s" % ("", R.c("非运行：" + ", ".join(stopped[:6]), "dim")))

    # ---- 告警面板
    section("告警")
    active = [(k, v) for k, v in lv.items() if v in ("warn", "crit")]
    if not active:
        print("  " + R.c("✓", "green", "bold") + " 所有已启用阈值均在正常范围")
    else:
        active.sort(key=lambda kv_: -LEVEL_PRIORITY.get(kv_[0], 0))
        for k, v in active:
            tip = ALERT_HINT.get(k, "")
            print("  %s %-14s %s" % (R.lvl(v), k, R.c(tip, "dim") if tip else ""))
    info = [(k, v) for k, v in lv.items() if v == "info"]
    if info:
        print("  " + R.c("  提示：" + ", ".join(k for k, _ in info), "dim"))

    worst = worst_level(lv)
    print()
    print("  " + R.c("总体：", "bold") + R.c({"ok": "正常", "info": "正常（有提示）", "warn": "注意", "crit": "异常"}[worst],
                                             LEVEL_COLOR[worst], "bold") +
          R.c("   采样间隔 %.2fs · 阈值可用 --cpu-warn/--mem-warn/--disk-warn/--temp-warn 调整" % cpu["sample_s"], "dim"))
    print()


ALERT_HINT = {
    "mem": "可用内存偏低：检查内存占用最高的进程，或考虑加内存",
    "swap": "交换分区被大量使用，性能会明显下降",
    "swap_io": "内存正在颠簸（持续换入换出）：整机响应会明显变慢",
    "disk": "磁盘接近写满：清理或扩容，满了会导致服务写入失败",
    "inode": "inode 用尽：即使还有空间也建不了新文件（通常是海量小文件）",
    "coretemp": "CPU 温度偏高：检查散热/风扇，持续高温会降频",
    "gpu": "GPU 温度偏高：检查机箱散热与风扇",
    "load": "负载相对 vCPU 偏高：有任务在排队",
    "top_cpu": "有单进程 CPU 占用接近满载",
    "top_mem": "有单进程占用大量内存",
    "journal_errors": "近期错误日志突发：journalctl -p err -b 复查",
    "oom": "发生过 OOM：内存耗尽时内核杀掉了进程",
    "throttle": "出现降频/过热记录：散热或功耗受限",
    "failed_units": "有 systemd 单元处于 failed 状态",
    "failed_timers": "有 systemd 定时器失败",
    "battery": "电池在放电且电量偏低：接上电源或准备关机",
    "docker_down": "有容器不是运行状态",
}


def worst_level(levels):
    worst = "ok"
    for v in levels.values():
        if LEVEL_ORDER[v] > LEVEL_ORDER[worst]:
            worst = v
    return worst


def fmt_num(x):
    return "-" if x is None else "%.2f" % x


def fmt_pct(x):
    return "-" if x is None else "%.1f%%" % x


def shorten(s, n):
    s = "" if s is None else str(s)
    if len(s) <= n:
        return s
    return s[: n - 1] + "…"


def alert_message(levels, limit=4):
    active = [(k, v) for k, v in levels.items() if v in ("warn", "crit")]
    active.sort(key=lambda kv_: (-LEVEL_ORDER[kv_[1]], -LEVEL_PRIORITY.get(kv_[0], 0)))
    if not active:
        return "dsysmon: 正常，无告警"
    shown = active[:limit]
    parts = ["%s(%s)" % (k, v) for k, v in shown]
    more = "" if len(active) <= limit else " 等 %d 项" % len(active)
    return "dsysmon: %d 项告警 -> %s%s" % (len(active), " ".join(parts), more)


# ---------------------------------------------------------------- 其它输出


def render_json(snap, args):
    out = dict(snap)
    out["program"] = {"name": PROG, "version": VERSION, "alert": alert_message(snap["levels"])}
    print(json.dumps(out, ensure_ascii=False, indent=2, default=str))


def save_snapshot(snap, path):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".dsysmon-")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(snap, fh, ensure_ascii=False, indent=2, default=str)
        os.replace(tmp, path)
        return path
    except OSError as e:
        return "保存失败：%s" % e


def watch(args):
    cycle = 0
    try:
        print("\033[?25l", end="")  # 隐藏光标
        while True:
            cycle += 1
            snap = collect(args)
            out = render(snap, args, refresh_note="· 第 %d 次刷新 · Ctrl-C 退出" % cycle)
            if args.save:
                save_snapshot(snap, args.save)
            sys.stdout.flush()
            time.sleep(max(0.5, args.watch_interval - snap["cpu"]["sample_s"]))
            print("\033[H\033[J", end="")  # 回到顶部并清屏
    except KeyboardInterrupt:
        pass
    finally:
        print("\033[?25h", end="")  # 恢复光标
    return 0


# ---------------------------------------------------------------- CLI


def parse_thresholds(args):
    th = Thresholds()
    for name, val in DEFAULTS.items():
        setattr(th, name, val)
    overrides = {
        "cpu_warn": "load_warn_pct",
        "mem_warn": "mem_warn", "mem_crit": "mem_crit",
        "disk_warn": "disk_warn", "disk_crit": "disk_crit",
        "temp_warn": "coretemp_warn", "temp_crit": "coretemp_crit",
        "gpu_warn": "gpu_warn", "gpu_crit": "gpu_crit",
    }
    if getattr(args, "cpu_warn", None) is not None:
        th.load_warn = args.cpu_warn / 100.0
    if getattr(args, "cpu_crit", None) is not None:
        th.load_crit = args.cpu_crit / 100.0
    for attr in ("mem_warn", "mem_crit", "disk_warn", "disk_crit", "temp_warn",
                 "temp_crit", "gpu_warn", "gpu_crit"):
        v = getattr(args, attr, None)
        if v is None:
            continue
        target = {"temp_warn": "coretemp_warn", "temp_crit": "coretemp_crit"}.get(attr, attr)
        setattr(th, target, float(v))
    return th


def build_parser():
    p = argparse.ArgumentParser(
        prog=PROG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="手动调用的服务器监测面板（只读，不修改任何系统状态）",
        epilog="""\
示例:
  dsysmon                     一次性快照并打印面板
  dsysmon -w                  每 3 秒刷新，Ctrl-C 退出
  dsysmon -w -i 5             每 5 秒刷新
  dsysmon --json | jq .mem    输出完整 JSON，便于脚本消费
  dsysmon --alert-at disk,mem 只对磁盘/内存相关项做非零退出（便于告警）
  dsysmon --disk-detail       额外执行 du -x -d1 / 看根目录占用 Top

启用的阈值（默认，可用参数覆盖）:
  内存 %.0f%% / %.0f%%   交换 %.0f%% / %.0f%%   磁盘 %.0f%% / %.0f%%   inode %.0f%% / %.0f%%
  CPU 温度 %.0f/%.0f°C   GPU 温度 %.0f/%.0f°C   负载 %.0f/%.0f × vCPU
  近 24h 错误日志 %d / %d 行   OOM 任意一条即 CRIT   失败 systemd 单元即 CRIT
""" % (
            DEFAULTS["mem_warn"], DEFAULTS["mem_crit"], DEFAULTS["swap_warn"], DEFAULTS["swap_crit"],
            DEFAULTS["disk_warn"], DEFAULTS["disk_crit"], DEFAULTS["inode_warn"], DEFAULTS["inode_crit"],
            DEFAULTS["coretemp_warn"], DEFAULTS["coretemp_crit"], DEFAULTS["gpu_warn"], DEFAULTS["gpu_crit"],
            DEFAULTS["load_warn"], DEFAULTS["load_crit"],
            DEFAULTS["journal_err_warn"], DEFAULTS["journal_err_crit"],
        ),
    )
    p.add_argument("-w", "--watch", action="store_true", help="持续刷新（默认关闭，需显式启用）")
    p.add_argument("-i", "--interval", type=float, default=0.6,
                   help="CPU/网络采样窗口秒数，用于算差值（默认 0.6）")
    p.add_argument("--watch-interval", type=float, default=3.0, help="watch 模式刷新周期，秒（默认 3）")
    p.add_argument("-n", "--top", type=int, default=8, help="进程列表条数（默认 8）")
    p.add_argument("--json", action="store_true", help="输出完整 JSON")
    p.add_argument("--save", nargs="?", const=os.path.join(STATE_DIR, "last.json"), default=None,
                   metavar="PATH", help="把快照存成 JSON（默认 %s）" % os.path.join(STATE_DIR, "last.json"))
    p.add_argument("--report", action="store_true", help="列出全部挂载点与全部温度传感器，不截断")
    p.add_argument("--battery-state", default=None, metavar="PATH",
                   help="累计电量状态文件路径（默认 %s）" % BATTERY_STATE_DEFAULT)
    p.add_argument("--no-battery-track", action="store_true",
                   help="不读写累计电量状态文件（面板仍显示当前电量与功率）")
    p.add_argument("--serve", action="store_true",
                   help="以 HTTP 服务方式运行，供手机 App 通过 IPv6 拉取盘面与 JSON")
    p.add_argument("--port", type=int, default=8899, help="HTTP 服务端口（默认 8899）")
    p.add_argument("--bind", default="::", metavar="ADDR",
                   help="监听地址（默认 :: 即所有 IPv6；--bind 127.0.0.1 则只有本机能连）")
    p.add_argument("--allow-ipv4", action="store_true",
                   help="同时接受 IPv4 访问（默认只接受 IPv6，避免暴露给内网 IPv4）")
    p.add_argument("--token", default=None, metavar="TOKEN",
                   help="访问令牌；不给则从状态文件读取，文件不存在时随机生成并保存")
    p.add_argument("--cache-seconds", type=float, default=5.0,
                   help="快照缓存秒数，多人/多端同时刷新时避免重复采样（默认 5）")
    p.add_argument("--auto-refresh-ms", type=int, default=0,
                   help="盘面默认自动刷新毫秒数。默认 0 = 关闭。"
                        "为什么默认关：选项卡是单选按钮，点它**不改 URL**，所以自动刷新"
                        "无法知道你正在看哪一页，只会在你翻到「温度」时把你弹回概览。"
                        "想看自动刷新就点页脚的「自动 10s」。")
    p.add_argument("--pair", action="store_true",
                   help="启动时生成一个短配对码（8 字符），App 在「设置→配对码」里填它即可"
                        "自动换取访问令牌；重启后换新码。不开启则只能手工填令牌")
    p.add_argument("--no-auth", action="store_true",
                   help="关闭令牌校验（仅在已用 --bind 127.0.0.1 或隧道内可达时才合理）")
    p.add_argument("--dashboard-file", default=None, metavar="PATH",
                   help="把盘面 HTML 定时写到此文件，供手机 App 经 SFTP 读取"
                        "（复用已授权的 SFTP 密钥，不需要新授权，也不需要端口转发）")
    p.add_argument("--dashboard-file-interval", type=float, default=20.0,
                   help="写盘间隔秒数（默认 20；每次写都要采样一次，别设太小）")
    p.add_argument("--no-color", action="store_true", help="不使用 ANSI 颜色")
    p.add_argument("--disk-detail", action="store_true", help="额外运行 du -x -d1 / 统计根目录占用")
    p.add_argument("--check-timers", action="store_true", help="额外检查失败的 systemd 定时器")
    p.add_argument("--journal-hours", type=int, default=24, help="日志检查窗口小时数（默认 24）")
    p.add_argument("--alert-at", default=None, metavar="LIST",
                   help="只用这些项决定退出码（逗号分隔，见源码 LEVEL_PRIORITY）")
    p.add_argument("--no-alert-exit", action="store_true", help="退出码永远是 0")
    for name, help_ in (
        ("cpu-warn", "CPU 负载告警百分比（相对 vCPU 数）"), ("cpu-crit", "CPU 负载严重百分比"),
        ("mem-warn", "内存使用率告警百分比"), ("mem-crit", "内存严重百分比"),
        ("disk-warn", "磁盘使用率告警百分比"), ("disk-crit", "磁盘严重百分比"),
        ("temp-warn", "CPU 温度告警摄氏度"), ("temp-crit", "CPU 温度严重摄氏度"),
        ("gpu-warn", "GPU 温度告警摄氏度"), ("gpu-crit", "GPU 温度严重摄氏度"),
    ):
        p.add_argument("--" + name, type=float, default=None, help=help_)
    p.add_argument("-V", "--version", action="version", version="%s %s" % (PROG, VERSION))
    return p


def main(argv=None):
    global R
    args = build_parser().parse_args(argv)
    R = Renderer(not args.no_color)
    args.th = parse_thresholds(args)

    if getattr(args, "serve", False):
        return serve(args)
    if args.watch:
        if args.json:
            print("%s: --watch 与 --json 不能同时使用" % PROG, file=sys.stderr)
            return 2
        return watch(args)

    snap = collect(args)
    if args.json:
        render_json(snap, args)
    else:
        render(snap, args)
    if args.save:
        path = save_snapshot(snap, args.save)
        if not args.json:
            print("  快照已保存：" + str(path))

    if args.no_alert_exit:
        return 0
    levels = snap["levels"]
    if args.alert_at:
        wanted = {x.strip() for x in args.alert_at.split(",") if x.strip()}
        unknown = wanted - set(LEVEL_PRIORITY)
        if unknown:
            print("%s: 未知告警项 %s" % (PROG, ",".join(sorted(unknown))), file=sys.stderr)
            return 2
        levels = {k: v for k, v in levels.items() if k in wanted}
    worst = worst_level(levels)
    if worst == "crit":
        return 2
    if worst == "warn":
        return 1
    return 0


TOKEN_FILE = os.path.join(STATE_DIR, "token")
PAIR_FILE = os.path.join(STATE_DIR, "pair")


def make_pair_code():
    """生成一个短配对码（人能抄的），用于 App 首次换取真令牌。

    为什么要配对码而不是让用户抄 32 位令牌：随机串抄错一位就是 401，
    而 401 在页面上看起来像"连不上"，排查成本极高。配对码短、可核对，
    且**窗口可控**（进程重启即换新码），换到令牌后就不必再管它。
    """
    return base64.b32encode(os.urandom(5)).decode().rstrip("=")   # 8 个字符，无易混符号


def write_pair_code(code):
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        fd = os.open(PAIR_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(code + "\n")
    except OSError:
        pass


def load_or_make_token(args):
    """令牌来源优先级：--token > 状态文件 > 随机生成并落盘（权限 600）。

    状态文件里已有令牌时**不重新生成** —— 这是刻意的：否则每次重启服务，
    手机上已存的令牌就失效了。

    ⚠️ 注意这个"只有文件不存在才生成"的语义**曾经造成过一次排查困难**：
    用户说"令牌自己变了"，而服务端这边只打印令牌值、不打印它是**新建还是复用**，
    于是无法从日志判断到底换没换。现在新建时多打一行，把这件事变成可查的。
    另外：文件被删/被清空 → 下次启动会静默生成新令牌（旧令牌立即失效），
    这种情况也只有那行日志能看出来。
    """
    if args.token:
        return args.token.strip()
    txt = read_text(TOKEN_FILE)
    if txt and txt.strip():
        return txt.strip()
    tok = base64.urlsafe_b64encode(os.urandom(24)).decode().rstrip("=")
    print("%s: 状态文件里没有令牌 —— 本次**新生成**一个并写入 %s（旧令牌若有则立即失效）"
          % (PROG, TOKEN_FILE), file=sys.stderr, flush=True)
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(tok + "\n")
    except OSError as e:
        print("%s: 警告：令牌无法保存（%s），重启后需要重新配对" % (PROG, e), file=sys.stderr)
    return tok


class SnapshotCache:
    """带 TTL 与单飞（single-flight）的快照缓存。

    手机端可能同时刷新多个页面 / 多次点刷新，若每次都重新采样，既浪费
    （每次采样要 sleep 一个窗口）也会让功耗与负载白涨。这里保证 TTL 内
    只采样一次，且并发请求共享同一次采样结果。
    """

    def __init__(self, args, ttl):
        self.args = args
        self.ttl = max(0.0, ttl)
        self._snap = None
        self._ts = 0.0
        self._lock = threading.Lock()

    def get(self, force=False):
        with self._lock:
            now = time.time()
            if not force and self._snap is not None and (now - self._ts) < self.ttl:
                return self._snap, self._ts, True
            snap = collect(self.args)
            self._snap, self._ts = snap, time.time()
            return snap, self._ts, False


def collect_public_addresses():
    """给手机端做地址发现的辅助信息：本机的全局 IPv6 / IPv4 列表。

    为了让"隧道读地址"这条既有做法有 HTTP 版本，这里直接给出同样的数据。
    IPv6 取自 /proc/net/if_inet6（0444 全局可读，收窄到只能 SFTP 的账号也读得到）。
    """
    out = {"ipv6": [], "ipv4": []}
    txt = read_text("/proc/net/if_inet6") or ""
    for line in txt.splitlines():
        f = line.split()
        if len(f) < 6 or len(f[0]) != 32 or f[3] != "00":  # scope 00 = global
            continue
        try:
            addr = socket.inet_ntop(socket.AF_INET6, bytes.fromhex(f[0]))
        except (ValueError, OSError):
            continue
        out["ipv6"].append(addr)
    out["ipv4"] = [ip for ip in primary_ip() if ":" not in ip]
    return out


DASHBOARD_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN"__UICLS__><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="dark light">
__AUTO__
<title>dsysmon · __HOST__</title>
<style>
 /* 配色与安卓端「系统监控」App 的品牌主题对齐（青蓝系）：只统一底色/卡片/描边/强调色，
    语义色 ok/warn/crit/info 原样保留。仍然零外部资源、零脚本——页面由服务端每 20s 重写，
    手机端 WebView 或浏览器直接渲染，改样式不用重装 APK。 */
 :root{--bg:#0e1415;--bg-a:rgba(14,20,21,.88);--card:#161d1e;--card-2:#1b2324;
       --fg:#e6edf3;--dim:#8b949e;--ok:#3fb950;--warn:#d29922;--crit:#f85149;--info:#58a6ff;
       --line:#2b3436;--accent:#4dd9df;--shadow:none}
 /* 下面两组 .force-* 供 App 显式指定主题（WebView 自己报的 prefers-color-scheme
    未必跟 App 的深浅色一致：窗口主题是 Light.* 时它会报 light，而 Compose 里是深色，
    结果就是"外壳黑、页面白"。App 会在 URL 上带 ?ui=dark|light，文件模式则在 <html> 上加类）。 */
 html.force-dark{--bg:#0e1415;--bg-a:rgba(14,20,21,.88);--card:#161d1e;--card-2:#1b2324;
       --fg:#e6edf3;--dim:#8b949e;--line:#2b3436;--accent:#4dd9df;--shadow:none}
 html.force-light{--bg:#f5fafa;--bg-a:rgba(245,250,250,.9);--card:#fff;
       --card-2:#f1f7f7;--fg:#171d1d;--dim:#5c6a6b;--line:#d7e2e3;--accent:#00696d;
       --shadow:0 1px 2px rgba(16,36,38,.06),0 1px 8px rgba(16,36,38,.04)}
 @media (prefers-color-scheme: light){:root{--bg:#f5fafa;--bg-a:rgba(245,250,250,.9);--card:#fff;
       --card-2:#f1f7f7;--fg:#171d1d;--dim:#5c6a6b;--line:#d7e2e3;--accent:#00696d;
       --shadow:0 1px 2px rgba(16,36,38,.06),0 1px 8px rgba(16,36,38,.04)}}
 *{box-sizing:border-box}
 html{-webkit-text-size-adjust:100%}
 body{margin:0;padding:0 12px calc(28px + env(safe-area-inset-bottom));background:var(--bg);color:var(--fg);
      font:15px/1.55 -apple-system,"Noto Sans CJK SC","PingFang SC",Roboto,sans-serif;
      font-variant-numeric:tabular-nums}
 header{position:sticky;top:0;z-index:5;display:flex;flex-wrap:wrap;gap:8px;align-items:baseline;
        justify-content:space-between;margin:0 -12px 12px;padding:12px 14px 10px;
        background:var(--bg-a);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);
        border-bottom:1px solid var(--line)}
 h1{font-size:19px;line-height:1.2;margin:0}
 .meta{color:var(--dim);font-size:12px;margin-top:3px}
 .hdr-badge{font-size:11px;border:1px solid var(--line);border-radius:999px;padding:2px 9px;color:var(--dim)}
 .hdr-badge.live{color:var(--ok);border-color:var(--ok)}
 .grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));align-items:start}
 .card{color:var(--fg);background:var(--card);border:1px solid var(--line);border-radius:14px;padding:13px 15px;overflow:hidden;
       box-shadow:var(--shadow);transition:border-color .15s ease,background-color .15s ease}
 .card.crit{border-color:var(--crit)} .card.warn{border-color:var(--warn)}
 /* 卡片自身带档位类时只改描边：档位色留给 .pill 与行内 <span class="warn"> 这类用法 */
 .card.ok,.card.warn,.card.crit,.card.info{color:var(--fg)}
 h2{font-size:12.5px;margin:0 0 8px;color:var(--dim);font-weight:600;letter-spacing:.05em}
 .row{display:flex;gap:10px;justify-content:space-between;align-items:baseline;padding:4px 0}
 .row+.row{border-top:1px solid var(--line)}
 .k{color:var(--dim);flex:0 0 auto;max-width:46%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .v{text-align:right;word-break:break-word;font-weight:500}
 .card.crit .v:last-child{color:var(--crit)}
 footer{margin:18px 2px 0;color:var(--dim);font-size:12px;display:flex;gap:14px;flex-wrap:wrap}
 a{color:var(--info);text-decoration:none;border-bottom:1px solid transparent;transition:border-color .15s}
 a:hover{border-bottom-color:currentColor}
 .pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;border:1px solid currentColor}
 .ok{color:var(--ok)} .warn{color:var(--warn)} .crit{color:var(--crit)} .info{color:var(--info)}
 /* 折叠卡：用原生 <details>，不引脚本。
    刻意**不做选项卡**：选项卡状态是页面级的，任何一次页面加载/重载都会重置它，
    用户就会觉得"自己跳回概览"（踩过两轮）。合成一页就没有"跳"这件事；
    一页信息多，所以核心两项默认展开、其余收起。 */
 details.card{padding:0}
 /* 触摸屏上没有真正的 hover：:hover 会粘在最后点过的那张卡上，而 summary 的
    背景是直角矩形，圆角卡片里就会看到"内外框没对齐"（点下一张才恢复）。
    所以改成 :active（只按下瞬间）+ [open]（展开的那张头部持续高亮），
    并且让 summary 自己带圆角、卡片 overflow:hidden —— 直角背景永远不可能露出来。 */
 details.card>summary{cursor:pointer;list-style:none;display:flex;align-items:center;gap:8px;
                      padding:12px 15px;border-radius:14px;transition:background-color .15s ease}
 details.card>summary:active{background:var(--card-2)}
 details.card>summary::-webkit-details-marker{display:none}
 details.card>summary::before{content:"\25B8";color:var(--dim);font-size:12px;
                              display:inline-block;transition:transform .18s ease}
 details.card[open]>summary::before{transform:rotate(90deg)}
 details.card[open]>summary{background:var(--card-2);border-radius:14px 14px 0 0;border-bottom:1px solid var(--line)}
 details.card>summary>h2{margin:0}
 details.card>summary .sum{margin-left:auto;color:var(--dim);font-size:12px;
                           max-width:52%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 details.card .body{margin:0;padding:11px 15px 13px}
 @media (prefers-reduced-motion: reduce){*{transition:none !important}}
</style></head>
<body>
<header>
  <div>
    <h1>__HOST__ 服务器监测</h1>
    <div class="meta">__ISO__ · __OVERALL__ __STATE__</div>
  </div>
</header>
<div class="grid">__CARDS__</div>
<footer>
__LINKS__
  <span>__ALERT__</span>
</footer>
</body></html>
"""


def build_dashboard_html(snap, levels, token, alert, refresh_ms, file_mode=False, ui_mode=None):
    """自包含盘面：不引任何外部资源、不执行任何脚本。

    刻意**不用 %-格式化**：CSS 里满是 `100%` / `46%` 这类百分号，用 %-格式化
    会不断踩"未转义百分号"的坑（已踩过一次：`text-size-adjust:100%` 让整页 500）。
    这里统一走占位符替换。
    """
    def h(x):
        return html.escape("" if x is None else str(x))

    def card(title, rows, level="ok", is_open=False, summary=""):
        """一张折叠卡。

        `summary` 是**收起时也显示**的一行摘要 —— 这样折叠不等于信息消失，
        扫一眼就知道每张卡现在什么状态（否则用户还得逐个展开）。
        is_open 决定默认展开；核心两项默认开，其余收起。
        """
        body = "".join(
            '<div class="row"><span class="k">%s</span><span class="v">%s</span></div>' % (h(k), v)
            for k, v in rows
        )
        return (
            '<details class="card %s"%s><summary><h2>%s</h2><span class="sum">%s</span></summary>'
            '<div class="body">%s</div></details>'
        ) % (level, " open" if is_open else "", h(title), summary, body)

    def tag(level):
        return {"ok": "正常", "info": "提示", "warn": "注意", "crit": "异常"}[level]

    # —— 概要卡
    cpu, mem, load = snap["cpu"], snap["mem"], snap["load"]
    overview = [
        ("主机", "%s · %s" % (snap["host"]["hostname"], snap["host"]["os"])),
        ("运行时长", human_dur(snap["host"]["uptime_s"])),
        ("负载", "%s / %s / %s（%d vCPU）" % (fmt_num(load[0]), fmt_num(load[1]), fmt_num(load[2]), cpu["count"])),
        ("CPU", "%.1f%%" % (cpu["used_pct"] or 0)),
    ]
    # GPU 使用率放进概要：这是最常看的一眼指标
    for g in snap["gpus"][:1]:
        overview.append(("GPU", "%.0f%% 使用 · %.0f°C · 显存 %s/%s" % (
            g["util_pct"] or 0, g["celsius"] or 0,
            human_bytes(g["mem_used"], 1), human_bytes(g["mem_total"], 1))))
    overview += [
        ("内存", "%.1f%%（%s / %s）" % (mem["used_pct"], human_bytes(mem["used"]), human_bytes(mem["total"]))),
    ]
    if mem["swap_total"]:
        overview.append(("交换", "%.1f%%（%s / %s）" % (mem["swap_pct"], human_bytes(mem["swap_used"]), human_bytes(mem["swap_total"]))))
    cards = []   # 单页：扁平列表，顺序即显示顺序

    def add(c):
        cards.append(c)

    cpu_pct = snap["cpu"]["used_pct"] or 0
    gpu_bit = (" · GPU %.0f%%" % (snap["gpus"][0]["util_pct"] or 0)) if snap["gpus"] else ""
    add(card("概要", overview, worst_level(levels), is_open=True,
             summary="CPU %.0f%% · 内存 %.0f%%%s" % (cpu_pct, snap["mem"]["used_pct"], gpu_bit)))

    # —— 电池
    bat = snap["host"].get("battery")
    if bat:
        rows = [("电量", "%s%% %s" % (bat.get("capacity"), bat.get("status") or ""))]
        if bat.get("energy_now_uwh") is not None and bat.get("energy_full_uwh"):
            rows.append(("总电量", "%s / %s" % (fmt_wh(bat["energy_now_uwh"]), fmt_wh(bat["energy_full_uwh"]))))
        rows.append(("电池功率", fmt_power(bat.get("power_uw"))))
        if bat.get("health_pct") is not None:
            rows.append(("健康度", "%.0f%%" % bat["health_pct"]))
        if bat.get("cycle_count") is not None:
            rows.append(("循环次数", "%d 次" % bat["cycle_count"]))
        nrg = snap["host"].get("battery_energy")
        if nrg and nrg.get("available"):
            rows.append(("累计电量", "放电 %s / 充入 %s" % (
                fmt_wh(nrg["discharged_wh"] * 1e6), fmt_wh(nrg["charged_wh"] * 1e6))))
        add(card("电池 / 电源", rows, levels.get("battery", "ok"),
                 summary="%s%% %s · %s" % (bat.get("capacity"), bat.get("status") or "",
                                           fmt_power(bat.get("power_uw")))))

    # —— 温度（传感器多，单独成卡）
    rows = []
    for t in sorted(snap["temps"], key=lambda x: -x["celsius"]):
        crit = ("临界 %.0f°C" % t["crit"]) if t.get("crit") else ""
        rows.append(("%s %s" % (
            t["chip"].replace("-virtual-0", "").replace("-isa-0000", "").replace("-pci-", "/"), t["label"]),
            "%.1f°C%s" % (t["celsius"], (" · " + crit) if crit else "")))
    if rows:
        hottest = max(snap["temps"], key=lambda x: x["celsius"])
        add(card("温度", rows, levels.get("coretemp", "ok"), is_open=True,
                 summary="最高 %.1f°C（%s）· 共 %d 项" % (
                     hottest["celsius"], hottest["label"], len(snap["temps"]))))

    # —— 磁盘 / 网络（细节页保持信息密度）
    rows = []
    for m in snap["disks"]["mounts"][:6]:
        rows.append((m["mount"], "%.1f%% · 可用 %s" % (m["used_pct"], human_bytes(m["avail"]))))
    if rows:
        worst_disk = max(snap["disks"]["mounts"], key=lambda m: m["used_pct"], default=None)
        add(card("磁盘", rows, levels.get("disk", "ok"),
                 summary=("%s %.0f%% · 可用 %s" % (worst_disk["mount"], worst_disk["used_pct"],
                                                   human_bytes(worst_disk["avail"]))
                          if worst_disk else "")))
    rows = []
    for n, (rx, tx) in sorted((snap["net"].get("rates") or {}).items()):
        rows.append((n, "↓ %s · ↑ %s" % (human_rate(rx), human_rate(tx))))
    sock = snap["net"].get("sockets") or {}
    rows.append(("TCP", "总计 %d · ESTABLISHED %d" % (
        sock.get("total", 0), (sock.get("by_state") or {}).get("ESTABLISHED", 0))))
    if sock.get("listening_ports"):
        rows.append(("监听", ", ".join(str(p) for p in sock["listening_ports"][:10])))
    _rate = sum(v[0] + v[1] for v in (snap["net"].get("rates") or {}).values())
    add(card("网络", rows, summary="合计 %s · TCP %d" % (human_rate(_rate), sock.get("total", 0))))

    # —— 风扇
    fans = snap.get("fans") or {}
    if fans.get("available") and not fans.get("stale"):
        frows = []
        names = ["风扇 1", "风扇 2"]
        for i, rpm in enumerate(fans.get("rpms") or []):
            frows.append((names[i] if i < len(names) else "风扇 %d" % (i + 1), "%d rpm" % rpm))
        frows.append(("包温", "%.1f°C" % (fans.get("package_c") or 0)))
        frows.append(("最高转速", "%d rpm" % (fans.get("max_rpm") or 0)))
        add(card("风扇", frows, "ok",
                 summary="%s rpm · 包温 %.1f°C" % (fans.get("max_rpm") or 0,
                                                   fans.get("package_c") or 0)))
    else:
        add(card("风扇", [("状态", h(fans.get("reason") or "不可用"))], "info"))

    # —— 进程
    # 进程 CPU 的口径：单核占满 = 100%，整机占满 = 核数×100%。
    # 直接把 800%（8 核全占）摆出来容易被误读，所以两种都给：
    # 先给"整机占比"，括号里给"相当于几个核"。
    ncore = max(1, snap["cpu"]["count"] or 1)
    rows = [("%s (pid %s)" % (p["comm"], p["pid"]),
             "整机 %.1f%% · 折合 %.2f 核" % (p["pcpu"] / ncore, p["pcpu"] / 100.0))
            for p in snap["procs"]["top_cpu"][:5]]
    rows += [("%s (pid %s)" % (p["comm"], p["pid"]), "内存 %s" % human_bytes(p["rss"], 1)) for p in snap["procs"]["top_mem"][:3]]
    _top = snap["procs"]["top_cpu"][0] if snap["procs"]["top_cpu"] else None
    add(card("进程", rows, summary=("占用最高 %s（整机 %.1f%%）" % (_top["comm"], _top["pcpu"] / ncore)
                                    if _top else "")))

    # —— 服务与日志
    je = snap["journal"]
    rows = [
        ("内核 OOM", "%d 次" % je.get("oom", 0)),
        ("错误日志突发", "近 %d 分钟 %d 条" % (BURST_WINDOW_MIN, je.get("error_burst") or 0)),
        ("失败单元", ", ".join(snap["failed_units"]) if snap["failed_units"] else "无"),
    ]
    dock = snap.get("docker")
    if dock and dock.get("available"):
        rows.append(("Docker", "%d 运行 / %d 容器" % (dock["running"], dock["total"])))
    add(card("服务与日志", rows, worst_level(
        {"j": levels.get("journal_errors", "ok"), "o": levels.get("oom", "ok")}),
        summary="OOM %d · 错误突发 %d · 失败单元 %d" % (
            je.get("oom", 0), je.get("error_burst") or 0, len(snap["failed_units"]))))

    # —— 告警
    active = sorted([(k, v) for k, v in levels.items() if v in ("warn", "crit")],
                    key=lambda kv_: (-LEVEL_ORDER[kv_[1]], -LEVEL_PRIORITY.get(kv_[0], 0)))
    if active:
        alert_rows = [(ALERT_HINT.get(k, k), tag(v)) for k, v in active]
    else:
        alert_rows = [("所有已启用阈值均在正常范围", "正常")]
    alert_card = card("告警", alert_rows, worst_level(levels),
                      summary=("无告警" if not active else "%d 项：%s" % (
                          len(active), "、".join(k for k, _ in active[:3]))))
    add(alert_card)

    overall = worst_level(levels)
    # 自动刷新的 URL 必须带上当前选项卡，否则每刷一次都被弹回概览页
    # （用户报过这个："刷新后会自动跳到概览"）。
    # 文件模式下**不自动刷新**：那份 HTML 是静态的、无法记住选中的选项卡，
    # 自动刷新只会在你正看"温度"页时把你弹回去 —— 宁可手动点 App 的「刷新」。
    if not refresh_ms:
        auto = ""
    elif file_mode:
        auto = ""
    else:
        # 单页模式：刷新就是重新加载同一页，不需要带任何选项卡参数
        auto = '<meta http-equiv="refresh" content="%d; url=?token=%s">' % (
            int(refresh_ms / 1000), h(token))
    state_line = ('<span class="hdr-badge">已缓存</span>' if snap.get("_cached")
                  else '<span class="hdr-badge live">刚采样 %.1fs</span>' % snap["cpu"]["sample_s"])
    if file_mode:
        # 文件模式下页面是被 App 经 SFTP 读下来、以 file:// 或 loadData 方式打开的：
        # 那些带 token 的链接点了也没有服务器可回，只会误导，所以整块去掉。
        # 刷新完全交给 meta refresh（auto 已按文件模式的周期参数设好）。
        links = "  <span>此页面由服务器定时写入，手机端经 SFTP 读取后展示</span>"
    else:
        t = h(token)
        # 单页模式：刷新就是重新加载这一页，不需要带任何选项卡参数
        links = (
            '  <a href="?token=%s">立即刷新</a>\n'
            '  <a href="?token=%s&amp;auto=10">自动 10s</a>\n'
            '  <a href="?token=%s&amp;auto=0">关闭自动</a>\n'
            '  <a href="/api/snapshot?token=%s">JSON</a>'
        ) % (t, t, t, t)
    # —— 选项卡：用**单选按钮 + CSS** 实现，不依赖脚本
    #
    # 为什么不用链接：盘面文件模式是静态 HTML（经 SFTP 读下来直接展示），
    # 没有服务器可回，链接式选项卡在那种模式下根本切不动。单选按钮 + :checked
    # 两种模式都能用，而且仍然满足"页面上不执行任何脚本"。
    # —— 单页输出
    #
    # 刻意**不做选项卡**（做过两轮都失败）：选项卡状态是页面级的 ——
    # 用链接 → 刷新回默认页；用单选按钮 → 同样问题，而且点它不改 URL，
    # 任何一次重载都会冲掉用户手选的那一页。用户反馈正是"翻到温度又自己跳回概览"。
    # 合成一页之后不存在"跳"这件事。一页信息多，所以用原生 <details> 折叠：
    # 核心两项默认展开、其余收起；每张卡收起时仍显示一行摘要，折叠不等于信息消失。
    visible = cards or [card("本页", [("无内容", "")], "info")]

    out = (DASHBOARD_TEMPLATE
            .replace("__CARDS__", "".join(visible))
            .replace("__LINKS__", links)
            .replace("__AUTO__", auto)
            .replace("__HOST__", h(snap["host"]["hostname"]))
            .replace("__ISO__", h(snap["iso"]))
            .replace("__OVERALL__", '<span class="pill %s">%s</span>' % (overall, tag(overall)))
            .replace("__STATE__", state_line)
            .replace("__UICLS__", (' class="force-%s"' % ui_mode) if ui_mode in ("dark", "light") else "")
            .replace("__ALERT__", h(alert))
            .replace("__TOKEN__", h(token)))

    # 防线：**渲染结果**里若还剩 __XXX__，它会当普通文字显示在页面上
    # （已经漏过一个 __NAV__，用户真的在页面上看见了"__NAV__"这四个字）。
    # 检查的是结果而不是模板 —— 模板里本来就该有占位符。
    leftover = re.findall(r"__[A-Z]+__", out)
    if leftover:
        raise AssertionError(
            "盘面渲染后仍有未替换的占位符：%s（它们会原样显示在页面上）" % sorted(set(leftover)))
    return out




def _loopback4(host):
    """IPv4 回环判定（隧道兜底时客户端就是它）。"""
    return host in ("127.0.0.1", "localhost") or host.startswith("127.")


def _dashboard_writer(args, token, stop_event):
    """定时把盘面写成文件，给"经已授权 SFTP 读取"这条兜底路径用。

    为什么要这条路径：手机在很多网络下根本到不了服务器的校园网 IPv6 地址
    （手机常在自己的运营商网段，与服务器不在同一 /64），端口转发又需要
    额外的 authorized_keys 授权。而**读文件**只需要本来就已经授权好的 SFTP，
    任何网络都能走（和传输助手的下载同一条链路）。
    """
    path = args.dashboard_file
    interval = max(5.0, args.dashboard_file_interval)
    out_dir = os.path.dirname(path)
    if out_dir:
        try:
            os.makedirs(out_dir, exist_ok=True)
        except OSError as e:
            print("%s: 警告：无法创建 %s（%s），盘面文件不会写出" % (PROG, out_dir, e),
                  file=sys.stderr, flush=True)
            return
    while not stop_event.is_set():
        try:
            snap = collect(args)
            html_doc = build_dashboard_html(
                snap, snap["levels"], token, alert_message(snap["levels"]),
                int(interval * 1000), file_mode=True,
            )
            fd, tmp = tempfile.mkstemp(dir=out_dir or ".", prefix=".dash-")
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(html_doc)
            os.chmod(tmp, 0o600)   # 含令牌，别给别人读
            os.replace(tmp, path)
        except Exception as e:  # noqa: BLE001 - 写盘失败绝不能拖垮 HTTP 服务
            print("%s: 盘面文件写出失败：%s" % (PROG, e), file=sys.stderr, flush=True)
        stop_event.wait(interval)


def serve(args):
    """HTTP 服务模式：手机 App 通过 IPv6 拉取盘面与 JSON。"""
    global R
    R = Renderer(False)
    token = load_or_make_token(args)
    pair_state = {"active": False, "code": ""}
    if args.pair:
        pair_state["code"] = make_pair_code()
        pair_state["active"] = True
        write_pair_code(pair_state["code"])
    cache = SnapshotCache(args, args.cache_seconds)
    auth_on = not getattr(args, "no_auth", False)
    bind = args.bind or "::"
    allow_v4 = args.allow_ipv4

    class Handler(BaseHTTPRequestHandler):
        server_version = "%s/%s" % (PROG, VERSION)
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *a):
            # 默认日志会把 token 写进访问日志，这里显式打码
            line = fmt % a
            line = re.sub(r"token=[A-Za-z0-9_\-]+", "token=***", line)
            # 刻意**不自己打时间戳**：交给 journald。
            # 本机的系统时区是 Etc/UTC，而使用者 shell 里设了 TZ=Asia/Shanghai；
            # 服务进程没有继承那个 TZ，于是应用侧打出的时间与 systemd 自己的
            # 时间戳会差 8 小时 —— 同一行日志两个时间，排查时极易看错。
            # 直接输出消息，让 journald 用它唯一的时间源。
            sys.stderr.write("%s\n" % line)

        # —— 工具
        def _client_ok(self):
            """默认只接受真正的 IPv6 访问，外加**本机回环**。

            为什么必须放行回环：SSH 本地转发（隧道兜底）是从服务器自己的
            127.0.0.1 发起连接的；若一律拒绝 IPv4，那条兜底路径会 403，
            而 App 侧只看得到"隧道建好了却打不开"，极难排查。
            """
            host = self.client_address[0]
            if allow_v4:
                return True
            if ":" in host:
                low = host.lower()
                # 双栈监听下 IPv4 会以 ::ffff:a.b.c.d 形式出现，那仍是 IPv4
                if low.startswith("::ffff:"):
                    return _loopback4(low.split("::ffff:", 1)[1])
                return True
            return _loopback4(host)

        def _token_ok(self):
            if not auth_on:
                return True
            q = parse_qs(urlparse(self.path).query)
            given = (q.get("token") or [""])[0]
            if not given:
                given = self.headers.get("X-Dsysmon-Token", "")
            return secrets.compare_digest(given, token)

        def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
            raw = body.encode("utf-8") if isinstance(body, str) else body
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def _deny(self, code, msg, extra_headers=None):
            self._send(code, json.dumps({"error": msg, "code": code}, ensure_ascii=False),
                       extra=extra_headers)

        def do_GET(self):
            # 任何渲染/解析异常都不能让连接挂死：手机端等超时的体验最差。
            # 统一兜成 500 并留下可诊断的日志。
            try:
                self._route()
            except Exception as e:  # noqa: BLE001 - 服务端必须兜住一切
                import traceback
                traceback.print_exc()
                try:
                    self._send(500, json.dumps(
                        {"error": "%s: %s" % (type(e).__name__, e)}, ensure_ascii=False))
                except Exception:
                    pass

        def _route(self):
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/") or "/"
            # 查询串在这里统一解析：配对、健康检查、令牌校验都要用它，
            # 而 /api/pair 在令牌校验之前处理（否则鸡生蛋）。
            q = parse_qs(parsed.query)

            if not self._client_ok():
                self._deny(403, "仅接受 IPv6 访问（服务端未开 --allow-ipv4）")
                return
            if path == "/api/health":
                # 供手机端做连通性探测：不需要令牌，不泄露任何监测数据
                self._send(200, json.dumps({"ok": True, "program": PROG, "version": VERSION}))
                return
            if path == "/api/pair":
                # 不需要令牌：这就是"用配对码换令牌"的入口，否则鸡生蛋。
                # 安全性靠三点：① 码是随机的且只在 --pair 期间有效；
                # ② 每次进程重启换新码；③ 换成功后立即作废。
                given = (q.get("code") or [""])[0].strip().upper()
                if not pair_state.get("active"):
                    self._deny(403, "该服务未开启配对（服务器需用 --pair 启动）")
                    return
                if not secrets.compare_digest(given, pair_state.get("code", "")):
                    self._deny(403, "配对码不对")
                    return
                pair_state["active"] = False        # 一次性
                try:
                    os.remove(PAIR_FILE)
                except OSError:
                    pass
                self._send(200, json.dumps({"token": token, "port": args.port}))
                return
            if not self._token_ok():
                self._deny(401, "token 无效或缺失", {"WWW-Authenticate": 'Bearer realm="dsysmon"'})
                return

            if path == "/api/address":
                info = collect_public_addresses()
                # 带上令牌本身，方便 App 首次连接时自动取到、省掉"去服务器上抄一串
                # 32 位随机字符"这一步。**不新增泄露面**：这个端点本来就要令牌才能访问，
                # 能看到它的人已经持有令牌了。
                info["token_hint"] = token
                info["port"] = args.port
                self._send(200, json.dumps(info, ensure_ascii=False))
                return
            if path in ("/api/snapshot", "/json"):
                snap, ts, cached = cache.get(force=q.get("force", ["0"])[0] == "1")
                snap = dict(snap)
                snap["_cached"] = cached
                snap["_served_at"] = datetime.fromtimestamp(ts).isoformat(timespec="seconds")
                snap["program"] = {"name": PROG, "version": VERSION,
                                   "alert": alert_message(snap["levels"])}
                self._send(200, json.dumps(snap, ensure_ascii=False, default=str))
                return
            if path == "/":
                snap, ts, cached = cache.get(force=q.get("force", ["0"])[0] == "1")
                snap = dict(snap)
                snap["_cached"] = cached
                refresh_ms = args.auto_refresh_ms
                if "auto" in q:
                    try:
                        refresh_ms = max(0, int(q["auto"][0]) * 1000)
                    except ValueError:
                        pass
                ui_mode = q.get("ui", [""])[0]
                html_doc = build_dashboard_html(snap, snap["levels"], token,
                                                alert_message(snap["levels"]), refresh_ms,
                                                ui_mode=ui_mode if ui_mode in ("dark", "light") else None)
                self._send(200, html_doc, "text/html; charset=utf-8")
                return
            if path == "/favicon.ico":
                self._send(204, b"", "image/x-icon")
                return
            self._deny(404, "未知路径")

        def do_HEAD(self):
            self.do_GET()

    # IPv6 双栈监听：:: 在 Linux 上默认同时接受 IPv4 映射地址，
    # 由 _client_ok() 在应用层拒绝，避免把端口暴露给内网 IPv4。
    if ":" in bind:
        class V6Server(ThreadingHTTPServer):
            address_family = socket.AF_INET6
        httpd = V6Server((bind, args.port), Handler)
    else:
        httpd = ThreadingHTTPServer((bind, args.port), Handler)
    httpd.daemon_threads = True

    addrs = collect_public_addresses()["ipv6"]
    print("%s %s 正在监听 [%s]:%d（仅 IPv6%s）" % (PROG, VERSION, bind, args.port,
                                                 "" if allow_v4 else "，IPv4 一律拒绝"))
    if addrs:
        for a in addrs:
            print("  盘面: http://[%s]:%d/?token=%s" % (a, args.port, token))
    print("  JSON: /api/snapshot  健康检查: /api/health  地址发现: /api/address")
    if pair_state["active"]:
        print("  配对码  : %s   ← 在 App 的「设置 → 配对码」填这一串即可自动取到令牌" % pair_state["code"])
        print("            （一次性：配对成功后失效；服务重启会换新码。也可用 --pair 手工重开）")
    else:
        print("  配对码  : 未开启（加 --pair 启动才生成）")
    print("  访问令牌: %s" % token)
    print("            （手机 App 的「设置 → 访问令牌」可填这串；权限 600 存在 %s）" % TOKEN_FILE)
    if args.dashboard_file:
        print("  盘面文件: %s（每 %.0f 秒重写，供 SFTP 读取）"
              % (args.dashboard_file, max(5.0, args.dashboard_file_interval)))
    print("  Ctrl-C 停止")
    # 显式 flush：以 systemd / 管道方式运行时 stdout 是块缓冲，进程被 SIGTERM
    # 结束时缓冲区不会落盘 —— 那几行（尤其 token 与盘面 URL）对使用者是必要的。
    sys.stdout.flush()
    stop_event = threading.Event()
    if args.dashboard_file:
        threading.Thread(
            target=_dashboard_writer, args=(args, token, stop_event), daemon=True,
        ).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n%s: 已停止" % PROG, file=sys.stderr)
    finally:
        stop_event.set()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        os._exit(0)
