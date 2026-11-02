"""图片侧：判断"要不要 OCR" + 调用本地 PaddleOCR-VL（llama-server, OpenAI 兼容）。"""
from __future__ import annotations

import base64
import json
import mimetypes
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .config import Config
from .util import ImageInfo

# 文件名里出现这些词 → 基本可以确定是截图/扫描件/票据
_DOC_NAME_RE = re.compile(
    r"screenshot|screen[-_ ]?shot|截屏|截图|长图|scan|扫描|打印屏|printscreen|"
    r"mmexport|wechatimg|wx_camera|receipt|invoice|发票|票据|收据|凭证|"
    r"text-|分享文本|聊天记录|photo[-_ ]?scan|document|doc[-_]?\d",
    re.I,
)


@dataclass
class OcrResult:
    text: str = ""
    engine: str = ""
    error: str = ""
    elapsed: float = 0.0

    @property
    def ok(self) -> bool:
        return bool(self.text.strip()) and not self.error


def should_ocr(name: str, info: ImageInfo, cfg: Config) -> tuple[bool, str]:
    """返回 (是否 OCR, 理由)。"""
    mode = (cfg.models.ocr_mode or "auto").lower()
    if mode == "off":
        return False, "配置 ocr_mode=off"
    if mode == "always":
        return True, "配置 ocr_mode=always"
    if _DOC_NAME_RE.search(name):
        return True, "文件名像截图/文档/票据"
    if cfg.models.ocr_skip_camera and info.is_camera_photo:
        return False, f"相机照片（{info.camera[:40]}），按 ocr_skip_camera 跳过"
    if info.width and info.height:
        if 1.6 <= info.aspect_tall <= 2.4 and max(info.width, info.height) >= 720:
            return True, "竖长屏幕比例且无相机信息，判定为截图"
    if (info.fmt or "").upper() in {"PNG", "WEBP"} and not info.is_camera_photo:
        return True, "PNG/WEBP 且无相机信息，判定为截图"
    return False, "auto 模式未命中截图/文档特征"


def _encode(path: Path) -> str:
    mime = mimetypes.guess_type(path.name)[0] or "image/png"
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode()


def run_ocr(path: Path, cfg: Config, prompt: str | None = None) -> OcrResult:
    import time
    t0 = time.time()
    payload = {
        "model": "paddleocr-vl",
        "temperature": 0,
        "max_tokens": 8192,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": prompt or cfg.models.ocr_prompt},
                {"type": "image_url", "image_url": {"url": _encode(path)}},
            ],
        }],
    }
    url = f"http://{cfg.models.ocr_host}:{cfg.models.ocr_port}/v1/chat/completions"
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=cfg.models.ocr_timeout) as r:
            resp = json.loads(r.read())
    except urllib.error.HTTPError as exc:
        return OcrResult(error=f"OCR HTTP {exc.code}: {exc.read()[:200]!r}",
                         engine="paddleocr-vl", elapsed=time.time() - t0)
    except Exception as exc:  # noqa: BLE001
        return OcrResult(error=f"OCR 请求失败: {exc}", engine="paddleocr-vl",
                         elapsed=time.time() - t0)
    text = ((resp.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    return OcrResult(text=text.strip(), engine="paddleocr-vl", elapsed=time.time() - t0)
