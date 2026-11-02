"""两个本地模型服务的探活与拉起。

    Qwen3-4B     127.0.0.1:8080   分类/摘要（文本）
    PaddleOCR-VL 127.0.0.1:8083   图片 OCR（视觉）

设计取舍：
- OCR **固定 CPU**（GPU_LAYERS=0）。Qwen3-4B 在 4GB 显存上已占 3.1GB，
  两个模型同时全量上 GPU 装不下（见项目的 OCR 部署说明）。
- Qwen 优先用 GPU：先探测 CUDA 是否真的可用（ctypes cuInit），
  不可用就 `LLM_CPU=1`，避免拉起一个必然崩掉的 CUDA 进程。
"""
from __future__ import annotations

import ctypes
import ctypes.util
import json
import os
import signal
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .config import Config

LLM_SCRIPT = Path.home() / ".local" / "bin" / "llm"
OCR_SCRIPT_FALLBACK = Path.home() / ".local" / "bin" / "ocr-serve"


@dataclass
class ServiceState:
    name: str
    up: bool
    detail: str = ""


# ---------------------------------------------------------------- 探活


def _http_json(url: str, timeout: float = 4.0) -> dict | None:
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
        return json.loads(body) if body else {}
    except Exception:  # noqa: BLE001
        return None


def health(host: str, port: int, timeout: float = 4.0) -> bool:
    """llama-server 的 /health：就绪时返回 {"status":"ok"}。"""
    for path in ("/health", "/v1/models"):
        data = _http_json(f"http://{host}:{port}{path}", timeout)
        if data is not None:
            return True
    return False


def cuda_available() -> bool:
    """真正试一次 cuInit —— 比看 nvidia-smi 可靠（掉卡后 nvidia-smi 会报 NVML 错）。"""
    try:
        lib = ctypes.CDLL(ctypes.util.find_library("cuda") or "libcuda.so.1")
    except OSError:
        return False
    try:
        return lib.cuInit(0) == 0
    except Exception:  # noqa: BLE001
        return False


def gpu_detail() -> str:
    if cuda_available():
        return "CUDA 可用"
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                             capture_output=True, text=True, timeout=8)
        if out.returncode == 0 and out.stdout.strip():
            return "驱动在但 CUDA 初始化失败（多半是休眠/挂起后掉卡，重启可恢复）"
    except Exception:  # noqa: BLE001
        pass
    return "无可用 CUDA"


# ---------------------------------------------------------------- 拉起


def _spawn(cmd: list[str], env: dict[str, str], log: Path) -> int:
    log.parent.mkdir(parents=True, exist_ok=True)
    fh = log.open("ab")
    fh.write(f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} {' '.join(cmd)}\n".encode())
    fh.flush()
    proc = subprocess.Popen(  # noqa: S603
        cmd, env=env, stdout=fh, stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL, start_new_session=True,
    )
    return proc.pid


def start_llm(cfg: Config) -> ServiceState:
    if health(cfg.models.llm_host, cfg.models.llm_port):
        return ServiceState("llm", True, "已在运行")
    if not LLM_SCRIPT.exists():
        return ServiceState("llm", False, f"找不到 {LLM_SCRIPT}")
    env = dict(os.environ)
    if cfg.models.llm_state_dir:
        env["LLM_STATE"] = cfg.models.llm_state_dir
    if not cuda_available():
        env["LLM_CPU"] = "1"
        env["LLM_NGL"] = "0"
    else:
        env.pop("LLM_CPU", None)
    env["LLM_PORT"] = str(cfg.models.llm_port)
    env["LLM_HOST"] = cfg.models.llm_host
    log = cfg.paths.state_dir / "llm-serve.log"
    _spawn([str(LLM_SCRIPT), "serve"], env, log)
    ok = wait_health(cfg.models.llm_host, cfg.models.llm_port, cfg.models.start_timeout)
    backend = "CPU" if env.get("LLM_CPU") == "1" else "GPU"
    return ServiceState("llm", ok,
                        ("已拉起（%s 后端）" % backend) if ok else f"拉起超时，见 {log}")


def start_ocr(cfg: Config) -> ServiceState:
    if health(cfg.models.ocr_host, cfg.models.ocr_port):
        return ServiceState("ocr", True, "已在运行")
    script = Path(cfg.models.ocr_start_script or OCR_SCRIPT_FALLBACK)
    if not script.exists():
        return ServiceState("ocr", False, f"找不到 {script}")
    env = dict(os.environ)
    env.update({
        "PORT": str(cfg.models.ocr_port),
        "GPU_LAYERS": "0",          # 固定 CPU：不与 8080 抢显存
        "MMPROJ_OFFLOAD": "0",
    })
    log = cfg.paths.state_dir / "ocr-server.log"
    pid = _spawn(["bash", str(script)], env, log)
    (cfg.paths.state_dir / "ocr.pid").write_text(str(pid))
    ok = wait_health(cfg.models.ocr_host, cfg.models.ocr_port, cfg.models.start_timeout)
    return ServiceState("ocr", ok, "已拉起（CPU）" if ok else f"拉起超时，见 {log}")


def stop_ocr(cfg: Config) -> bool:
    pid_file = cfg.paths.state_dir / "ocr.pid"
    if not pid_file.exists():
        return False
    try:
        pid = int(pid_file.read_text().strip())
    except ValueError:
        pid_file.unlink(missing_ok=True)
        return False
    try:
        os.killpg(os.getpgid(pid), signal.SIGTERM)
    except ProcessLookupError:
        pass
    except Exception:  # noqa: BLE001
        try:
            os.kill(pid, signal.SIGTERM)
        except Exception:  # noqa: BLE001
            pass
    for _ in range(30):
        if not health(cfg.models.ocr_host, cfg.models.ocr_port, 1.0):
            break
        time.sleep(0.5)
    pid_file.unlink(missing_ok=True)
    return True


def wait_health(host: str, port: int, timeout: int) -> bool:
    deadline = time.time() + max(5, timeout)
    while time.time() < deadline:
        if health(host, port, 3.0):
            return True
        time.sleep(2.0)
    return False


def llm_check(cfg: Config) -> bool:
    """抽取服务是否可用。远程模式下不探本地端口——本地 llama-server 可能压根没起。"""
    if cfg.models.llm_is_remote:
        return True
    return health(cfg.models.llm_host, cfg.models.llm_port)


def llm_label(cfg: Config) -> str:
    if cfg.models.llm_is_remote:
        chain = cfg.models.llm_remote_chain
        first = str(chain[0]).split(":", 1)
        tail = f"（{len(chain)} 级降级）" if len(chain) > 1 else ""
        return f"远程免费模型 {first[1] if len(first) > 1 else chain[0]}{tail}"
    return f"Qwen3-4B {cfg.models.llm_host}:{cfg.models.llm_port}"


def ensure(cfg: Config, need_llm: bool, need_ocr: bool, *, verbose=print) -> dict[str, ServiceState]:
    """按需拉起模型服务；返回各自状态。"""
    out: dict[str, ServiceState] = {}
    if need_llm:
        if cfg.models.llm_is_remote:
            # 远程：不需要拉起任何本地进程
            out["llm"] = ServiceState("llm", True, llm_label(cfg))
        else:
            st = ServiceState("llm", health(cfg.models.llm_host, cfg.models.llm_port), "已在运行")
            if not st.up and cfg.models.autostart:
                verbose("  Qwen3-4B 未运行，正在拉起…")
                st = start_llm(cfg)
            out["llm"] = st
    if need_ocr:
        st = ServiceState("ocr", health(cfg.models.ocr_host, cfg.models.ocr_port), "已在运行")
        if not st.up and cfg.models.autostart:
            verbose("  PaddleOCR-VL 未运行，正在拉起（CPU 模式，约需 20–60 秒）…")
            st = start_ocr(cfg)
        out["ocr"] = st
    return out


def status(cfg: Config) -> dict[str, ServiceState]:
    if cfg.models.llm_is_remote:
        llm_state = ServiceState("llm", True, llm_label(cfg))
    else:
        llm_up = health(cfg.models.llm_host, cfg.models.llm_port)
        llm_state = ServiceState(
            "llm", llm_up,
            f"{cfg.models.llm_host}:{cfg.models.llm_port} · {gpu_detail()}")
    ocr_up = health(cfg.models.ocr_host, cfg.models.ocr_port)
    return {
        "llm": llm_state,
        "ocr": ServiceState("ocr", ocr_up,
                            f"{cfg.models.ocr_host}:{cfg.models.ocr_port} · 固定 CPU"),
    }
