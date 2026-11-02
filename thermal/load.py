#!/usr/bin/env python3
"""重载基线：CPU 满载 + GPU（推理服务）持续请求，每秒记一次温度/频率/功耗。

用途：换硅脂、改风扇曲线、验证散热改造前后，需要一条可重复的「热负载曲线」。
不需要 root，只读 coretemp / cpufreq / nvidia-smi。

    python3 load.py                     # 默认 240 秒，8 线程
    HEAVY_SECONDS=600 python3 load.py   # 跑 10 分钟
    HEAVY_THREADS=4 python3 load.py     # 4 线程

输出：/tmp/heavy-cpu-gpu.csv（elapsed,pkg_c,freq_max_mhz,gpu_w,gpu_util）

GPU 负载是可选的：把 LLM_URL 指向一个 OpenAI 兼容端点（默认本机 llama.cpp
的 /v1/chat/completions）；没有端点或没有 NVIDIA 卡时，GPU 那几列留空，
CPU 部分照常跑。
"""
from __future__ import annotations

import json
import os
import subprocess
import threading
import time
import urllib.request

OUT = os.environ.get("OUT_FILE", "/tmp/heavy-cpu-gpu.csv")
THREADS = int(os.environ.get("HEAVY_THREADS", "8"))
DUR = float(os.environ.get("HEAVY_SECONDS", "240"))
COOL = float(os.environ.get("COOL_SECONDS", "60"))
LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8080/v1/chat/completions")
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen3-4b")
DEVNULL = subprocess.DEVNULL
PROMPT = ("请写一篇 800 字的说明文，主题是「城市里的旧书店」，"
          "要求分成若干段落，逐段展开，不要省略、不要缩短。")

gpu_stop = threading.Event()
gpu_stats = {"requests": 0, "errors": 0}


def find_package_temp() -> float | None:
    """coretemp 的 Package id 0（毫摄氏度 → °C）。找不到就返回 None。"""
    import glob

    for d in glob.glob("/sys/class/hwmon/hwmon*"):
        try:
            name = open(os.path.join(d, "name")).read().strip()
        except OSError:
            continue
        if name != "coretemp":
            continue
        for label in glob.glob(os.path.join(d, "temp*_label")):
            try:
                if open(label).read().strip() != "Package id 0":
                    continue
                return int(open(label.replace("_label", "_input")).read()) / 1000.0
            except (OSError, ValueError):
                continue
    return None


def cpu_freq_max() -> float:
    best = 0
    for i in range(os.cpu_count() or 8):
        try:
            best = max(best, int(open(
                f"/sys/devices/system/cpu/cpu{i}/cpufreq/scaling_cur_freq").read()))
        except (OSError, ValueError):
            pass
    return best / 1000


def gpu_power() -> tuple[float, float]:
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=power.draw,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5).stdout.strip()
        w, u = out.split(",")
        return float(w), float(u)
    except Exception:                            # noqa: BLE001
        return -1.0, -1.0


def load1() -> float:
    try:
        return float(open("/proc/loadavg").read().split()[0])
    except Exception:                            # noqa: BLE001
        return 99.0


def gpu_worker() -> None:
    """反复请求本机推理服务，让 GPU 持续满载。"""
    payload = {"model": LLM_MODEL, "max_tokens": 700, "temperature": 0.7,
               "messages": [{"role": "user", "content": PROMPT}]}
    data = json.dumps(payload).encode()
    while not gpu_stop.is_set():
        try:
            req = urllib.request.Request(
                LLM_URL, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=120) as r:
                r.read()
            gpu_stats["requests"] += 1
        except Exception:                        # noqa: BLE001
            gpu_stats["errors"] += 1
            time.sleep(2)


def main() -> int:
    print(f"=== 重载基线（CPU {THREADS} 线程 + GPU 持续推理）× {DUR:.0f}s ===",
          flush=True)
    print("--- 先等机器稳下来（包温 ≤46°C、load <0.6、GPU <15W 连续 2 分钟）",
          flush=True)
    stable = 0
    t_start = time.time()
    while time.time() - t_start < 900:
        t = find_package_temp()
        l = load1()
        w, u = gpu_power()
        tt = f"{t:.1f}°C" if t is not None else "?"
        print(f"    {time.strftime('%T')}  {tt}  load {l:.2f}  "
              f"GPU {w:.1f}W {u:.0f}%  (稳 {stable}/4)", flush=True)
        stable = stable + 1 if (t is not None and t <= 46.0 and l < 0.6
                                and w < 15.0) else 0
        if stable >= 4:
            break
        time.sleep(30)

    t0 = find_package_temp()
    print(f"--- 起点 {t0 if t0 is not None else float('nan'):.1f}°C，开始满载", flush=True)

    procs = [subprocess.Popen(["nice", "-n", "5", "yes"], stdout=DEVNULL,
                              stderr=DEVNULL) for _ in range(THREADS)]
    th = threading.Thread(target=gpu_worker, daemon=True)
    th.start()

    rows: list[tuple] = []

    def flush() -> None:
        with open(OUT, "w", encoding="utf-8") as fh:
            fh.write("elapsed,pkg_c,freq_max_mhz,gpu_w,gpu_util\n")
            for a, b, c, d, e in rows:
                fh.write(f"{a:.1f},{'' if b is None else f'{b:.1f}'},"
                         f"{c:.0f},{d:.1f},{e:.0f}\n")

    try:
        start = time.monotonic()
        while True:
            el = time.monotonic() - start
            if el >= DUR + COOL:
                break
            if el >= DUR:
                for p in procs:
                    p.terminate()
                gpu_stop.set()
            t = find_package_temp()
            w, u = gpu_power()
            rows.append((el, t, cpu_freq_max(), w, u))
            flush()
            if int(el) % 15 == 0:
                print(f"    {el:5.0f}s  {t if t is not None else float('nan'):5.1f}°C  "
                      f"CPU {rows[-1][2]:4.0f} MHz  GPU {w:5.1f}W {u:3.0f}%  "
                      f"(请求 {gpu_stats['requests']})", flush=True)
            time.sleep(1.0)
    finally:
        gpu_stop.set()
        for p in procs:
            try:
                p.terminate()
            except Exception:                    # noqa: BLE001
                pass
        for p in procs:
            try:
                p.wait(timeout=3)
            except Exception:                    # noqa: BLE001
                p.kill()

    load_rows = [r for r in rows if r[0] <= DUR and r[1] is not None]
    if not load_rows:
        print("没有采到温度（coretemp 不可用？）", flush=True)
        return 1
    temps = [r[1] for r in load_rows]
    deltas = [b - a for a, b in zip(temps, temps[1:])]
    gpus = [r[3] for r in load_rows if r[3] > 0]
    print("\n================ 小结 ================")
    print(f"起点 {temps[0]:.1f}°C · 峰值 {max(temps):.1f}°C · "
          f"单秒最大温升 +{max(deltas):.1f}°C")
    if gpus:
        print(f"满载段 GPU 平均 {sum(gpus)/len(gpus):.1f}W · CPU 频率峰值 "
              f"{max(r[2] for r in load_rows):.0f} MHz · "
              f"完成 {gpu_stats['requests']} 次推理请求")
    print(f"CSV: {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
