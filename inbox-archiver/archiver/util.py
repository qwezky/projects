"""通用工具：类型判定、时间解析、文件名清洗、图片元数据。"""
from __future__ import annotations

import datetime as _dt
import hashlib
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------- 文件类型

KIND_BY_EXT: dict[str, str] = {}
for _kind, _exts in {
    "text": [".txt", ".md", ".markdown", ".rst", ".org", ".log", ".csv", ".tsv",
             ".json", ".jsonl", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf",
             ".py", ".js", ".ts", ".tsx", ".java", ".kt", ".c", ".h", ".cpp",
             ".sh", ".sql", ".html", ".htm", ".xml", ".css", ".srt", ".vtt",
             ".tex", ".bib"],
    "image": [".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff",
              ".heic", ".heif", ".avif"],
    "video": [".mp4", ".mov", ".mkv", ".avi", ".webm", ".3gp", ".m4v", ".flv", ".wmv"],
    "audio": [".mp3", ".m4a", ".wav", ".ogg", ".amr", ".aac", ".flac", ".opus"],
    "archive": [".zip", ".tar", ".gz", ".tgz", ".7z", ".rar", ".xz", ".bz2"],
    "document": [".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                 ".odt", ".ods", ".odp", ".epub"],
    "apk": [".apk", ".apks", ".xapk"],
}.items():
    for _e in _exts:
        KIND_BY_EXT.setdefault(_e, _kind)

# 文本类里"能直接读出正文"的
READABLE_TEXT_EXT = {
    ".txt", ".md", ".markdown", ".rst", ".org", ".log", ".csv", ".tsv",
    ".json", ".jsonl", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf",
    ".py", ".js", ".ts", ".tsx", ".java", ".kt", ".c", ".h", ".cpp",
    ".sh", ".sql", ".html", ".htm", ".xml", ".css", ".srt", ".vtt", ".tex", ".bib",
}


def detect_kind(name: str) -> str:
    return KIND_BY_EXT.get(Path(name).suffix.lower(), "other")


# ---------------------------------------------------------------- 时间解析

_FILENAME_DATE_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    # Screenshot_2027-04-05-16-30-00-42_xxx.jpg / Record_2026-09-06-13-46-59_xx.mp4
    (re.compile(r"(20\d{2})-(\d{2})-(\d{2})[-_ ]?(\d{2})[-_.](\d{2})[-_.](\d{2})"), "%Y%m%d%H%M%S"),
    # IMG20270406071500.jpg / VID20270407193045.mp4
    (re.compile(r"(20\d{2})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})"), "%Y%m%d%H%M%S"),
    # 2026-10-13 或 2026_10_13
    (re.compile(r"(20\d{2})[-_.](\d{2})[-_.](\d{2})"), "%Y%m%d"),
    # 20261013
    (re.compile(r"(20\d{2})(\d{2})(\d{2})"), "%Y%m%d"),
]

# 毫秒时间戳（手机 App 生成的 text-1807315200000.txt）
_EPOCH_MS_RE = re.compile(r"(?<!\d)(1[0-9]{12})(?!\d)")
# 秒时间戳
_EPOCH_S_RE = re.compile(r"(?<!\d)(1[0-9]{9})(?!\d)")

EXIF_DATETIME_TAGS = ("DateTimeOriginal", "DateTimeDigitized", "DateTime")


def date_from_filename(name: str) -> _dt.datetime | None:
    """从文件名里尽力解析出时间；解析不出返回 None。"""
    stem = Path(name).stem
    m = _EPOCH_MS_RE.search(stem)
    if m:
        try:
            return _dt.datetime.fromtimestamp(int(m.group(1)) / 1000.0)
        except (OverflowError, OSError, ValueError):
            pass
    for pat, fmt in _FILENAME_DATE_PATTERNS:
        m = pat.search(stem)
        if not m:
            continue
        try:
            dt = _dt.datetime.strptime("".join(m.groups()), fmt)
        except ValueError:
            continue
        if 2000 <= dt.year <= 2100:
            return dt
    m = _EPOCH_S_RE.search(stem)
    if m:
        try:
            return _dt.datetime.fromtimestamp(int(m.group(1)))
        except (OverflowError, OSError, ValueError):
            pass
    return None


# ---------------------------------------------------------------- 图片元数据


@dataclass
class ImageInfo:
    ok: bool = False
    width: int = 0
    height: int = 0
    fmt: str = ""
    exif_datetime: _dt.datetime | None = None
    camera: str = ""          # Make/Model 等相机信息（有 → 是"拍的照片"）
    error: str = ""
    extra: dict = field(default_factory=dict)

    @property
    def is_camera_photo(self) -> bool:
        return bool(self.camera)

    @property
    def aspect_tall(self) -> float:
        """长边/短边，竖屏截图通常 1.7~2.3。短边为 0 时返回 0。"""
        if not self.width or not self.height:
            return 0.0
        lo, hi = sorted((self.width, self.height))
        return hi / lo if lo else 0.0


def _clean_exif(value: object) -> str:
    """EXIF 字符串常带尾部 \\x00 填充（如 b'MakerName\\x00\\x00...'），这里清掉。"""
    if value is None:
        return ""
    if isinstance(value, bytes):
        value = value.decode("utf-8", "ignore")
    return str(value).replace("\x00", "").strip()


def read_image_info(path: Path) -> ImageInfo:
    info = ImageInfo()
    try:
        from PIL import Image, ExifTags  # 延迟导入：纯文本任务不需要 PIL
    except Exception as exc:  # noqa: BLE001
        info.error = f"PIL 不可用: {exc}"
        return info
    try:
        with Image.open(path) as im:
            info.ok = True
            info.width, info.height = im.size
            info.fmt = im.format or ""
            raw = getattr(im, "getexif", lambda: None)()
            tags: dict[str, object] = {}
            if raw:
                for k, v in raw.items():
                    name = ExifTags.TAGS.get(k, str(k))
                    tags[name] = v
            for tag in EXIF_DATETIME_TAGS:
                val = tags.get(tag)
                if not val:
                    continue
                if isinstance(val, bytes):
                    val = val.decode("ascii", "ignore")
                try:
                    info.exif_datetime = _dt.datetime.strptime(str(val), "%Y:%m:%d %H:%M:%S")
                    break
                except ValueError:
                    continue
            make = _clean_exif(tags.get("Make"))
            model = _clean_exif(tags.get("Model"))
            lens = _clean_exif(tags.get("LensModel"))
            # EXIF 里 Make 常常是 Model 的前缀（"Maker" / "Maker X5 Pro"），别重复
            pieces = [] if (make and model.lower().startswith(make.lower())) else [make]
            pieces += [model, lens]
            info.camera = " ".join(x for x in pieces if x).strip()
            # 手机截图常带 Software 字段，但不带 Make/Model —— 不算相机
            if tags.get("Software") and not info.camera:
                info.extra["software"] = str(tags["Software"])
    except Exception as exc:  # noqa: BLE001
        info.error = f"读取图片元数据失败: {exc}"
    return info


# ---------------------------------------------------------------- 杂项


def sha256_of(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            b = fh.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


_BAD_CHARS = re.compile(r"[/\\\x00-\x1f<>:\"|?*]")


def safe_name(text: str, max_len: int = 40, fallback: str = "item") -> str:
    """把模型给的标题清成安全的文件名片段（保留中文）。"""
    text = unicodedata.normalize("NFKC", str(text or ""))
    text = _BAD_CHARS.sub(" ", text)
    text = re.sub(r"\s+", "-", text.strip())
    text = text.strip("-. ")
    # 去掉结尾像扩展名的东西（标题里常带 .jpg）
    text = re.sub(r"\.(jpe?g|png|gif|webp|mp4|mov|pdf|txt|md|zip|apk)$", "", text, flags=re.I)
    if len(text) > max_len:
        text = text[:max_len].rstrip("-. ")
    return text or fallback


def human_size(n: int) -> str:
    x = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if x < 1024 or unit == "TB":
            return f"{x:.0f} {unit}" if unit == "B" else f"{x:.1f} {unit}"
        x /= 1024
    return f"{x:.1f} TB"


def now_str() -> str:
    return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def read_text_best_effort(path: Path, max_bytes: int = 512 * 1024) -> str:
    """按 UTF-8 → GBK → 忽略错误 的顺序读文本，最多读 max_bytes。"""
    raw = path.read_bytes()[:max_bytes]
    for enc in ("utf-8", "utf-8-sig", "gb18030", "big5"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")
