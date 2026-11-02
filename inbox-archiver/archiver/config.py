"""配置：默认值 + config.toml 覆盖 + 环境变量覆盖。

优先级：环境变量 > config.toml > 内置默认值
所有路径都用绝对路径（会被 SSH 触发的后台进程调用，不能依赖 CWD）。

目录结构（全部英文名，路径可用配置改）：
    <archive>/raw/           原文件：手机「存档区」入口上传到这里（收件箱）
    <archive>/records/       档案：文本直接放；图片按 <名字>/ 打包（原图 + text.md）
    <archive>/todos/         事项：todos/年.月.日/ 每天一个 todos.md + todos.jsonl
"""
from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG_PATH = PROJECT_DIR / "config.toml"


def _env(key: str) -> str | None:
    v = os.environ.get(key)
    return v if v else None


@dataclass
class Paths:
    # 整个归档库的根；raw/records/todos 默认都在它下面
    root: Path = Path.home() / "phone-archive"
    raw: Path = Path.home() / "phone-archive" / "raw"
    records: Path = Path.home() / "phone-archive" / "records"
    todos: Path = Path.home() / "phone-archive" / "todos"
    state_dir: Path = field(default_factory=lambda: PROJECT_DIR / "run")


@dataclass
class Filters:
    # 明显是测试/临时残留的东西，不整理（原样留在 raw）
    ignore_globs: list[str] = field(default_factory=lambda: [
        "e2e*.bin", "*.part", "*.tmp", "*.crdownload", "*.filepart", ".*",
    ])
    # 文件修改时间距今超过这么久，才认定「传完了」
    min_stable_seconds: int = 30
    min_size: int = 1
    recursive: bool = False


@dataclass
class Models:
    llm_host: str = "127.0.0.1"
    llm_port: int = 8080
    llm_alias: str = "qwen3-4b"
    llm_timeout: int = 900
    llm_max_tokens: int = 900
    llm_temperature: float = 0.0
    # 待办抽取时喂进去的正文上限（字符）
    max_text_chars: int = 8000
    max_ocr_chars: int = 8000

    # ---- 远程免费模型（DSH 里已配好的路由）----
    # 留空 = 用上面的本地 llama-server（默认，行为与之前完全一致）。
    # 填了 llm_remote_chain 就走远程：密钥从 ~/.dsh/.credentials.yaml 的 refs
    # 只读读取，不写进本项目。详见 archiver/llm_remote.py 顶部注释。
    # 形如 ["modelscope:deepseek-ai/DeepSeek-V4-Pro", ...]，按顺序降级。
    llm_remote_chain: list[str] = field(default_factory=list)
    llm_min_interval: float = 1.0   # 免费档按账号限速，串行 + 最小间隔
    # 远程单次请求超时。本地的 llm_timeout=900 是给 CPU 推理留的余量，
    # 远程不该等这么久（挂住会白等 15 分钟）。
    llm_remote_timeout: int = 120
    # 远程的 max_tokens 必须比本地大：ZAI glm-4.5-flash 这类带思考链的模型
    # 实测会先写 1100~1900 字的 reasoning，预算给 900 时 content 直接返回空。
    llm_remote_max_tokens: int = 2000

    ocr_host: str = "127.0.0.1"
    ocr_port: int = 8083
    ocr_prompt: str = "OCR:"
    ocr_timeout: int = 1800
    # 存档区里的图片默认一律 OCR（用户要求）；off 可关，auto 只挑截图/文档类
    ocr_mode: str = "always"
    # auto 模式下，带相机 EXIF 的照片直接跳过 OCR
    ocr_skip_camera: bool = True

    autostart: bool = True
    # 留空 = 用 `llm` 脚本自己的默认目录 ~/.local/state/llm
    llm_state_dir: str = ""
    ocr_start_script: str = ""   # 留空 = 不自动拉起 OCR（需外部先起服务）
    start_timeout: int = 300

    @property
    def llm_is_remote(self) -> bool:
        """走远程免费模型时，不要去健康检查/拉起本地 llama-server。"""
        return bool(self.llm_remote_chain)


@dataclass
class Todos:
    # 待办事项只认「有日期」的；没写日期的不入 todos（用户明确要求）
    require_date: bool = True
    # 跨多日的事项最多铺开多少天（防止模型写出离谱的长区间把目录撑爆）
    max_span_days: int = 120
    # 早于今天这么多天的日期当成模型幻觉丢掉
    past_grace_days: int = 365
    future_limit_days: int = 1825

    # ---- 准确率优先：多路抽取 + 一致度聚类 + 材料在手复核 ----
    # 用户要求「不惜一切时间代价换取准确率」，所以默认开满。
    # 想要快：`inbox run --fast`，或把 passes 调成 1、verify 关掉。
    passes: int = 3                   # 主提示词跑几路（第 1 路 temp=0，其余采样）
    # 走远程大模型时用这个路数（2026-10-18 用户要求「用大模型就不用投那么多票」）。
    # 原因：sample_temperature=0 时第 2..N 路是**重复调用**，只靠浮点抖动产生差异，
    # 它们存在的意义是给弱模型（Qwen3-4B）兜住输出不稳。换成 V4-Pro 后一致性本就高，
    # 1 路主提示 + 1 路「换切分」已经够；多跑纯属浪费时间和魔粒。
    passes_remote: int = 1
    # ⚠️ 采样温度别调高：实测 0.5 会让 Qwen3-4B-Q4_K_M 跑偏 —— 一行一场的活动清单被写成
    # 几条空泛的「参加某日的讲座」（丢条目、把两场并成一场），还会多出「报名/参加」两条同名待办；
    # 0.2 仍会偶发「参加某日××专场讲座」这种拿栏目标题当活动名的弱标题；
    # 0.0 各路口径一致（几份真实材料多轮实测：该抽几条就抽几条、票数全满）。
    # 多路的意义只剩「换切分」那条不同提示词的路，以及兜住 GPU 浮点不确定性带来的偶发换说法。
    sample_temperature: float = 0.0
    alt_prompt: bool = True           # 再加一路「先抄时间点→谓语，再转待办」的换切分
    # ⚠️ 实测（「一份通知里四项」/「报名+活动两条」这两个用例）：复核那一路会**丢条目**
    # （4 条并成 3 条）并把「汇总报名名单」缩写成「汇总」。4B 模型当裁判比当选手更差，
    # 所以默认关掉；想试就设 true（多花一次调用）。
    verify: bool = False
    # 合并门槛：0.55 会把「报名X」并进「参加X」（只差一个动词、文本极像但语义相反），
    # 等于把分歧藏起来；0.7 只并近乎同义的写法，分歧会作为「多路说法不一致」露出来。
    merge_threshold: float = 0.6


@dataclass
class Preheat:
    """热启动：重负载前先用低强度负载把 EC 的风扇带起来（见 archiver/preheat.py）。

    ✅ 2026-10-15 用 EC 转速直读测出**它确实有效**，默认打开：
      * 风扇从 0 到 1400 rpm 需要 **约 24~42 秒的持续负载**（实测起转点 ~53 °C）；
      * 冷机直跑 OCR：峰值 **63 °C**、单秒最大温升 **+7 °C**；
        先预热（风扇已在 1400 rpm）再跑同一张图：峰值 **51 °C**、单秒 **+2 °C**；
      * 预热期间风扇一转到 1400 rpm，温度反而从 64 °C 掉到 51 °C —— 压得住。

    ⚠️ 关键在于 **min_seconds**：风扇要 20 多秒才起转，而"温度达标就停"会被
    一次 turbo 尖峰骗到（实测出现过 1.5 秒就"达标"退出），风扇根本没转起来 ——
    这正是 2026-10-15 早些时候那轮 A/B 误判"无收益"的原因。
    """
    enabled: bool = True
    # 至少跑这么久才允许"温度达标"提前收工：给风扇留出起转时间（实测 24~42s）
    min_seconds: float = 30.0
    # 抬到这个包温就停手（只在跑够 min_seconds 之后才生效）
    target_temp_c: float = 64.0
    # 上限，避免冷环境下无限等（也限制额外耗电与时间）
    max_seconds: float = 60.0
    # 4 线程 nice 10：实测升温 ≈0.2~0.4 °C/s（43→62 °C 约 45 秒）。
    # 曾经设 2 线程 → 45 秒只抬了 6 °C（43→49），根本到不了目标，等于白烧。
    threads: int = 4
    # 只对足够大的图预热：1080×1363 的截图（147 万像素）不预热，
    # 2481×3507 的扫描页（870 万像素）才预热 —— 小图 OCR 只要 2~11 秒
    min_pixels: int = 1500000
    poll_seconds: float = 0.5


@dataclass
class Behavior:
    # raw 里出现「既不是文本也不是图片」的东西时怎么办：
    #   error  = 留在 raw 不动，记一条 error（存档区本不该出现这些，App 会拦）
    #   move   = 原样搬进 records/
    #   ignore = 原样留在 raw，不记日志
    foreign_action: str = "error"
    # 模型服务拉不起来时：defer = 原样留在 raw 等下次；skip = 照样归档但抽不出待办
    on_model_error: str = "defer"
    # 手机上「上传完成 → 触发」之后，服务器先等这么久再扫，
    # 避开"最后一点字节还没落盘 / mtime 还很新"的窗口。默认 = 稳定窗口 + 5 秒。
    trigger_delay_seconds: int = 35
    # 内容重复（sha256 命中）的上传去向
    duplicate_dir: str = "_duplicate"
    # 文本类直接进 records 时，文件名冲突自动加 -2
    name_max: int = 80
    # 图片打包目录名最长字符数
    bundle_name_max: int = 60


@dataclass
class Config:
    paths: Paths = field(default_factory=Paths)
    filters: Filters = field(default_factory=Filters)
    models: Models = field(default_factory=Models)
    todos: Todos = field(default_factory=Todos)
    behavior: Behavior = field(default_factory=Behavior)
    preheat: Preheat = field(default_factory=Preheat)
    source: str = "defaults"

    # ---- 派生路径 ----
    @property
    def db_path(self) -> Path:
        return self.paths.state_dir / "state.db"

    @property
    def log_path(self) -> Path:
        return self.paths.state_dir / "archiver.log"

    @property
    def ocr_log_path(self) -> Path:
        return self.paths.state_dir / "ocr-server.log"

    @property
    def trigger_log_path(self) -> Path:
        return self.paths.state_dir / "trigger.log"

    def as_dict(self) -> dict:
        return {
            "archive_root": str(self.paths.root),
            "raw": str(self.paths.raw),
            "records": str(self.paths.records),
            "todos": str(self.paths.todos),
            "state_dir": str(self.paths.state_dir),
            "ocr_mode": self.models.ocr_mode,
            "foreign_action": self.behavior.foreign_action,
            "preheat": self.preheat.enabled,
        }


def _apply(obj, data: dict) -> None:
    for k, v in data.items():
        if not hasattr(obj, k):
            continue
        cur = getattr(obj, k)
        if isinstance(cur, Path):
            setattr(obj, k, Path(str(v)).expanduser())
        elif isinstance(cur, list) and isinstance(v, list):
            setattr(obj, k, [str(x) for x in v])
        else:
            setattr(obj, k, v)


def load(config_path: str | os.PathLike | None = None) -> Config:
    cfg = Config()
    explicit = bool(config_path) or bool(_env("INBOX_CONFIG"))
    path = Path(config_path) if config_path else Path(
        _env("INBOX_CONFIG") or DEFAULT_CONFIG_PATH)
    if not path.is_file() and explicit:
        # 显式指定的配置文件不存在时报错，避免"打错路径 → 悄悄用默认配置动真目录"
        raise FileNotFoundError(f"配置文件不存在：{path}")
    if path.is_file():
        with path.open("rb") as fh:
            raw = tomllib.load(fh)
        cfg.source = str(path)
        for section in ("paths", "filters", "models", "todos", "behavior", "preheat"):
            if isinstance(raw.get(section), dict):
                _apply(getattr(cfg, section), raw[section])

    # 环境变量覆盖（自动化 / 临时试跑）
    root = _env("ARCHIVE_ROOT")
    if root:
        cfg.paths.root = Path(root).expanduser()
    if _env("ARCHIVE_DIR"):
        cfg.paths.root = Path(_env("ARCHIVE_DIR")).expanduser()
    if root or _env("ARCHIVE_DIR"):
        r = cfg.paths.root
        cfg.paths.raw = r / "raw"
        cfg.paths.records = r / "records"
        cfg.paths.todos = r / "todos"
    if _env("INBOX_DIR"):
        cfg.paths.raw = Path(_env("INBOX_DIR")).expanduser()
    if _env("RECORDS_DIR"):
        cfg.paths.records = Path(_env("RECORDS_DIR")).expanduser()
    if _env("TODOS_DIR"):
        cfg.paths.todos = Path(_env("TODOS_DIR")).expanduser()
    if _env("INBOX_STATE_DIR"):
        cfg.paths.state_dir = Path(_env("INBOX_STATE_DIR")).expanduser()
    if _env("INBOX_OCR_MODE"):
        cfg.models.ocr_mode = _env("INBOX_OCR_MODE")
    if _env("INBOX_AUTOSTART"):
        cfg.models.autostart = _env("INBOX_AUTOSTART") not in ("0", "false", "no")
    if _env("INBOX_LLM_STATE"):
        cfg.models.llm_state_dir = _env("INBOX_LLM_STATE")

    cfg.paths.state_dir.mkdir(parents=True, exist_ok=True)
    return cfg
