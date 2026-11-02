"""热启动：重负载前用低强度负载把 EC 的风扇先带起来。

✅ 结论（2026-10-15 用 EC 转速直读测出来）：**有效，默认打开**。

为什么当初要试它
----------------
本机（Legion Y7000 2019 / i5-9300H）的风扇**完全由 EC 按自己的曲线控制**：
没有 pwm、`platform_profile` 不可用，ideapad 的 `fan_mode` 命令在这台机器上
是空壳（写 1、写 4 都不生效，读回恒 0）。所以只能顺着 EC 工作。

实测（2026-10-14，一张 2481×3507 的扫描页走 PaddleOCR-VL，CPU 4 线程）：
包温在**第 27→28 秒的 1 秒内从 58 °C 跳到 78 °C**，峰值 88 °C —— EC 的曲线
追不上这个功率阶跃，看起来像是"风扇没提前转起来"。

2026-10-15 的直读实测（转速从 EC RAM 读：FANS=偏移 6、FA2S=偏移 254，×100=rpm）
----------------------------------------------------------------------------
* **风扇从 0 到 1400 rpm 需要约 24~42 秒的持续负载**，起转点约 53 °C；
* 冷机直跑同一张图的 OCR：峰值 **63 °C**、单秒最大温升 **+7 °C**
  （风扇在第 ~42 秒才起转，之前 40 秒温度一路裸奔）；
* 先预热到风扇已在 1400 rpm 再跑同一张图：峰值 **51 °C**、单秒 **+2 °C**；
* 预热期间只要风扇转到 1400 rpm，温度就从 64 °C 掉回 51 °C —— 压得住。

**这推翻了我更早一轮的"无收益"结论，原因也找到了**：当时用的是"温度达标就停"，
而 CPU 一冲 turbo 就能在 1 秒内把包温读数顶到 61~63 °C → 预热 1.5 秒就退出，
**风扇根本来不及起转**（它要 20 多秒）。所以那轮 A/B 里 OCR 始终从 0 rpm 开始，
峰值当然一样。修法就是 `min_seconds`：跑够 30 秒才允许"温度达标"提前收工。

实测的升温速率（i5-9300H，nice 10）：4 线程 ≈0.2~0.4 °C/s（43→62 °C 约 45 秒）；
2 线程 45 秒只抬 6 °C —— 所以用 4 线程，别调回 2。

代价：每张大图多花约 30 秒（min_seconds）。想随时看转速：
    sudo <repo>/thermal/fan-rpm.sh watch 30

设计约束（都是踩过的坑）
------------------------
* **min_seconds 是这套东西能不能生效的关键**，别拿掉：风扇起转要 20 多秒，
  温度触发会被 turbo 尖峰骗到。
* 温度源必须按 hwmon 的 name/label 找（coretemp / "Package id 0"），
  **不能写死 hwmonN 编号** —— 编号会随硬件枚举变化（本机现在是 hwmon5，
  换内核或插拔外设都可能变）。
* 读不到温度 → 直接跳过并给出原因，**绝不因为预热失败挡住归档**。
* 负载进程必须能在任何情况下被收干净（finally）。
* 只对足够大的图预热：小截图 OCR 只要 2–11 秒，预热反而让总耗时翻几倍。
"""
from __future__ import annotations

import glob
import subprocess
import time
from dataclasses import dataclass

from .config import Config
from .util import detect_kind, read_image_info

_HWMON = "/sys/class/hwmon"


@dataclass
class PreheatResult:
    ran: bool
    reason: str
    seconds: float = 0.0
    start_c: float | None = None
    end_c: float | None = None
    threads: int = 0

    def line(self) -> str:
        if not self.ran:
            return f"热启动跳过（{self.reason}）"
        a = "-" if self.start_c is None else f"{self.start_c:.1f}"
        b = "-" if self.end_c is None else f"{self.end_c:.1f}"
        return (f"热启动 {self.seconds:.1f}s · 包温 {a} → {b} °C"
                f"（{self.threads} 线程，{self.reason}）")


def find_package_temp() -> float | None:
    """读 CPU 包温（摄氏度）。按 hwmon 的 name/label 找，不写死编号。"""
    for d in sorted(glob.glob(f"{_HWMON}/hwmon*")):
        try:
            with open(f"{d}/name", encoding="ascii", errors="replace") as fh:
                if fh.read().strip() != "coretemp":
                    continue
        except OSError:
            continue
        # 首选：label == "Package id 0"
        for lab in sorted(glob.glob(f"{d}/temp*_label")):
            try:
                with open(lab, encoding="ascii", errors="replace") as fh:
                    if fh.read().strip() != "Package id 0":
                        continue
                with open(lab.replace("_label", "_input"), encoding="ascii") as fh:
                    return int(fh.read().strip()) / 1000.0
            except (OSError, ValueError):
                continue
        # 退路：coretemp 的 temp1_input 按内核约定就是 package
        try:
            with open(f"{d}/temp1_input", encoding="ascii") as fh:
                return int(fh.read().strip()) / 1000.0
        except (OSError, ValueError):
            continue
    return None


def _spawn_load(threads: int):
    """起 N 个低优先级纯 CPU 负载。nice 10 是为了让温度**慢慢**爬上去。"""
    procs = []
    for _ in range(max(0, int(threads))):
        try:
            procs.append(subprocess.Popen(
                ["nice", "-n", "10", "yes"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        except OSError:
            break
    return procs


def _stop(procs) -> None:
    for p in procs:
        try:
            p.terminate()
        except Exception:  # noqa: BLE001
            pass
    for p in procs:
        try:
            p.wait(timeout=3)
        except Exception:  # noqa: BLE001
            try:
                p.kill()
            except Exception:  # noqa: BLE001
                pass


# 测试注入点：自检里替换这两个，就不会真烧 CPU、也不需要真温度
_read_temp = find_package_temp
_spawn = _spawn_load


def maybe_warm(cfg: Config, *, pixels: int | None = None, verbose=print,
               read_temp=None, spawn_load=None, sleep=time.sleep,
               clock=time.monotonic) -> PreheatResult:
    """按配置决定要不要热启动，并等到目标温度或超时。签名里的注入点供自检使用。"""
    pc = cfg.preheat
    if not pc.enabled:
        return PreheatResult(False, "配置 preheat.enabled=false")
    if int(pc.threads) <= 0:
        return PreheatResult(False, "preheat.threads=0")
    px = int(pixels or 0)
    if px < int(pc.min_pixels):
        return PreheatResult(False, f"图不够大（{px} < {pc.min_pixels} 像素）")

    rt = read_temp or _read_temp
    cur = rt()
    if cur is None:
        return PreheatResult(False, "读不到 CPU 包温（coretemp）")
    if cur >= pc.target_temp_c:
        return PreheatResult(False, f"包温已 {cur:.1f}°C ≥ 目标 {pc.target_temp_c:g}°C")

    spawn = spawn_load or _spawn
    procs = []
    t0 = clock()
    reason = "超时"
    min_s = float(getattr(pc, "min_seconds", 0.0) or 0.0)
    try:
        procs = spawn(pc.threads) or []
        while True:
            elapsed = clock() - t0
            temp = rt()
            # 关键：一定要跑够 min_seconds 才允许"温度达标"提前收工。
            # 风扇从 0 到 1400 rpm 要 24~42 秒持续负载，而 turbo 尖峰能在 1 秒内
            # 把包温读数顶到 62 °C —— 不加这道闸，预热会在风扇起转前就退出。
            if (elapsed >= min_s and temp is not None
                    and temp >= pc.target_temp_c):
                reason = "达标"
                break
            if elapsed >= pc.max_seconds:
                break
            sleep(pc.poll_seconds)
    finally:
        _stop(procs)

    res = PreheatResult(True, reason, seconds=clock() - t0, start_c=cur,
                        end_c=rt(), threads=int(pc.threads))
    if verbose:
        print("    " + res.line())
    return res


def warm_for(cfg: Config, paths, *, verbose=print, **kw) -> PreheatResult:
    """给一批待处理文件决定要不要预热：取其中最大图片的像素数做门控。"""
    px = 0
    for p in paths:
        try:
            if detect_kind(p.name) != "image":
                continue
            info = read_image_info(p)
            if info.width and info.height:
                px = max(px, int(info.width) * int(info.height))
        except Exception:  # noqa: BLE001
            continue
    return maybe_warm(cfg, pixels=px, verbose=verbose, **kw)
