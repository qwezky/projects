"""待办事项：让本机 Qwen3-4B 从「刚入档案的内容」里抽出**有日期**的行动项。

规则（用户定的）：
- 只认有日期的。没写日期/截止日的一律不入 todos —— 内容本身还在 records/ 里。
- 单日事项 → 那一天的目录里；跨多日事项 → 区间内**每一天**都有。
- 相对日期（"明天""下周五"）按材料的日期换算。

准确率优先（用户要求「不惜一切时间代价换准确率」）：
一份材料不是问一次模型就完事，而是
  1. **多路抽取**：主提示词跑 N 路（第 1 路 temp=0 求稳，其余带温度采样求广），
     再加一路**换切分方式**的提示词（先抄"时间点→谓语"，再据此转待办）；
  2. **按一致度聚类**：多路结果里日期重叠、说法相近的合成一条，记下"几路抽到"；
  3. **材料在手复核**：把原材料 + 带票数的候选清单交回模型，逐条判定
     （日期绑错动作的改或删、纯资讯删、漏掉的补），以原文为准。
每一步都有确定性的代码护栏兜底（见 _normalize）。
"""
from __future__ import annotations

import datetime as _dt
import difflib
import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from .config import Config

SYSTEM_PROMPT = (
    "你是一个中文待办事项抽取器。用户会把一份材料（通知、聊天记录、截图 OCR 文本、笔记等）交给你，"
    "你要挑出其中**需要当事人去做的事**，并给出它涉及的时间。"
    "只输出 JSON，不要输出解释、思考过程或 Markdown 代码块。"
)

# 状态通知里的一项（「某件事已完成 / 已取消」），和正经待办分开两个数组
_CLOSE_ITEM = {
    "type": "object",
    "properties": {
        "task": {"type": "string", "maxLength": 120},
        "evidence": {"type": "string", "maxLength": 200},
    },
    "required": ["task"],
    "additionalProperties": False,
}

SCHEMA = {
    "type": "object",
    "properties": {
        "todos": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "task": {"type": "string", "maxLength": 120},
                    "start": {"type": "string"},
                    "end": {"type": "string"},
                    "evidence": {"type": "string", "maxLength": 200},
                },
                "required": ["task", "start", "end"],
                "additionalProperties": False,
            },
        },
        "done": {"type": "array", "items": _CLOSE_ITEM},
        "cancelled": {"type": "array", "items": _CLOSE_ITEM},
    },
    "required": ["todos"],
    "additionalProperties": False,
}


@dataclass
class TodoEntry:
    task: str
    start: str            # YYYY-MM-DD
    end: str              # YYYY-MM-DD（与 start 相同 = 单日）
    evidence: str = ""
    votes: int = 1        # 多路抽取里有几路抽到它
    source_pass: str = "" # 主提示词 / 采样 / 换切分 / 复核补漏
    # 多路说法不一致时留下原文，让"模型其实没想清楚"这件事看得见
    variants: list[str] = field(default_factory=list)
    # 确定性核对（不靠模型）：依据那句话是否真在材料里；日期字面是否真在材料里
    evidence_grounded: bool = True
    date_literal: bool = True
    # 日期被护栏按依据里的相对说法换算过时，记下「旧→新（依据「本周日」换算）」
    redated: str = ""
    # 换算掉的那个旧日期本身。收尾要靠它认出「被搬走的那条」和它的孪生条目
    date_before: str = ""

    @property
    def span_days(self) -> int:
        try:
            return (_dt.date.fromisoformat(self.end)
                    - _dt.date.fromisoformat(self.start)).days + 1
        except ValueError:
            return 0


@dataclass
@dataclass
class CloseSignal:
    """材料通知「某件事已完成 / 已取消」时抽出来的关闭指令（不是待办）。"""
    task: str                  # 被通知的那件事，用材料里的说法
    state: str = "done"        # done=已完成 / cancelled=已取消
    evidence: str = ""         # 材料里的依据句


@dataclass
class TodoResult:
    entries: list[TodoEntry] = field(default_factory=list)
    # 材料里的状态通知（「已完成 / 已取消」）。不是待办，由调用方落到已有待办上
    closes: list[CloseSignal] = field(default_factory=list)
    dropped_no_date: int = 0
    dropped_insane: int = 0
    # 材料里一个时间词都没有 → 模型给的日期一律当编造，整批丢掉
    dropped_no_anchor: int = 0
    # 日期在自己抄的那句依据里没有落点（既没写字面、也换算不出来）
    dropped_unbound: int = 0
    # 被护栏按依据里的相对说法换算过的日期（旧→新）
    date_fixed: list[str] = field(default_factory=list)
    error: str = ""
    raw: str = ""
    model: str = ""
    passes: int = 1               # 这一轮实际跑了几路
    notes: list[str] = field(default_factory=list)


# ================================================================ 提示词


# 手机上传的文件名形如
#   Screenshot_<年-月-日-时-分-秒-厘>_<32位十六进制>.jpg
#   Image_<13位毫秒时间戳>_163.png / text-<13位毫秒时间戳>.txt
# 这种又长又带哈希的名字会让 4B 模型**稳定退化**：同一份「一行一场」的活动清单材料，
# 喂长名时固定少抽两场、并把标题退化成「参加某日的××、××等专场讲座」这种栏目名；
# 换成短标签后同一份材料就固定抽满、且每条都点得出活动名。
# 所以进提示词前先把文件名收成短标签（保留日期，方便相对日期换算时对照）。
_HASH_RE = re.compile(r"[0-9a-f]{8,}", re.I)
_LONG_DIGITS_RE = re.compile(r"\d{8,}")
_NAME_DATE_RE = re.compile(r"(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})")
_IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".tif", ".tiff"}


def friendly_source(name: str | None) -> str:
    """把上传文件名收成给模型看的短标签；已经够短够干净的照原样留着。"""
    raw = str(name or "").strip()
    if not raw:
        return "（未命名）"
    p = Path(raw)
    stem = p.stem or p.name
    if len(stem) <= 24 and not _HASH_RE.search(stem) and not _LONG_DIGITS_RE.search(stem):
        return stem
    m = _NAME_DATE_RE.search(stem)
    date = f"{m.group(1)}-{m.group(2)}-{m.group(3)}" if m else ""
    low = stem.lower()
    if "screenshot" in low or "screen" in low or "截图" in stem or "截屏" in stem:
        label = "手机截图"
    elif p.suffix.lower() in _IMAGE_EXT:
        label = "图片"
    else:
        label = "文本记录"
    return f"{label}_{date}" if date else label


def build_prompt(*, source_name: str, doc_date: str, today: str,
                 body_text: str, max_chars: int, variant: str = "main") -> str:
    """variant: main = 主提示词；pairs = 换切分（先列时间点→谓语）

    `today` 只为保持调用方签名兼容，**不进提示词**。给模型看「今天」（那其实是
    *处理*那天，不是材料那天）只会诱导它把已经过去的事项当过期丢掉 —— 换模型后
    立刻踩到过（清单第一项固定消失）。所以提示词里只说材料日期，并正面要求
    「材料里所有带日期的信息都要处理」。日期范围校验在 `_normalize` 里用 today 做，
    与提示词无关。
    """
    body = body_text.strip()[:max_chars]
    mentions = time_mentions(body)
    lines = [
        "## 材料信息",
        f"- 来源文件：{friendly_source(source_name)}",
        f"- 材料日期（换算相对日期时用它当基准）：{doc_date}",
        "",
    ]
    if variant == "pairs":
        lines += [
            "## 做法（分两步想，但只输出 JSON）",
            "**第一步**：把材料里出现的每一个时间点抄下来，并在它旁边写清楚"
            "**那句话的谓语是什么** —— 举行 / 开始 / 报名 / 截止 / 提交 / 办理 / 参加 / 结束。",
            "**第二步**：只有谓语表示「需要当事人做点什么」的时间点，才转成待办，"
            "日期就用那个时间点的日期；谓语是「举行/开始/开幕」的，那天要做的是**参加/出席**。",
            "把第一步想清楚再输出，但**输出里只放 JSON**，不要写第一步的草稿。",
            "",
        ]
    lines += [
        "## 抽取要求",
        "- 先把材料通读一遍，**逐条**找出所有「需要当事人去做、并且带时间」的事，尽量别漏。",
        "- **材料里所有带日期的信息都要处理**：已经过去的、今天的、将来的，都要各算一条，"
        "不许因为「日期已经过了」就跳过。",
        "- 算待办的：要提交、要办理、要参加、要准备、要回复、要交费、到期要还。",
        "- **活动/检查/培训/考试发生在某段时间的，也算待办**"
        "（例如「期中教学检查定于10月15日至10月18日进行」→ 要参加/要准备，"
        "start=2026-10-15，end=2026-10-18）。",
        "- 不算待办的：纯资讯、背景介绍、已经做完的事、感慨、闲聊、天气预报。",
        "- `task`：一句话说清要做什么，不超过 40 字，不要照抄整段。",
        "",
        "### ⚠️ 最容易错的一条：把日期绑到它**真正描述的那件事**上",
        "同一份材料里常有「报名」和「活动」两个动作，日期只属于其中一个。按下面判断：",
        "- 「将于 X 举行 / X 开始 / X 当天 / X 开幕 / X 下午举行」"
        "→ X 那天要做的是**参加/出席**，`task` 必须写「参加…」，**不是「报名…」**。",
        "- 「X 前报名 / 报名截止 X / X 前申请」→ X 是**报名/申请**的截止日，`task` 写「报名…」。",
        "- 「X 前提交 / X 截止 / X 前交」→ X 是**提交**的截止日，`task` 写「提交…」。",
        "- **活动有举行时间、而报名/申请没写时间时：只输出「参加…」这一条。**"
        "报名那条因为没有日期，按规则不输出 —— **不要**把举行日期安到报名上硬凑一条。",
        "- 一句话多件事时同理：日期绑在哪个动作上，要按材料里那句话的谓语来定，不要按标题定。",
        "",
        "### 不要替当事人做材料没做的选择",
        "- 材料同时给出**多个可选**组别/时段/方式（「建议…的报A组，…的报B组」）时，"
        "`task` 里要把选项留着，例如「参加机器人大赛（甲组/乙组，按自身情况选）」；"
        # 这句例子是**实测挑出来的**：换成别的写法（不放例子、放两条例子、
        # 甚至整路吐不出 JSON。模型对这句的措辞极度敏感，改动前务必重新实测。
        "**不要**只挑一个写进去 —— 你并不知道当事人属于哪一种。",
        "- 同理不要补材料里没有的地点、金额、材料清单、联系人。",
        "",
        "- `start` / `end`：这件事涉及的日期，格式严格 `YYYY-MM-DD`。",
        "  * 只有一天 → `start` 与 `end` 填同一个日期。",
        "  * 跨多天 → `start`=开始日，`end`=结束日。",
        "  * 相对说法（今天/明天/后天/下周三/本月底）按上面的「材料日期」换算成绝对日期。",
        "  * **材料里没写日期、也无法由相对说法算出来的，`start` 和 `end` 都填空字符串 `\"\"`。**",
        "    特别注意：**不要**因为「材料日期是某天」或「今天就是某天」就把那天填进去。宁可留空。",
        "- `evidence`：**只抄一句**，而且必须是**包含该日期、并描述那件事**的那一句"
        "（照抄，可截断到 200 字）。抄了整段 = 你还没把日期和动作绑清楚，会被判为不合格。",
        "- 一条都抽不到就返回 `{\"todos\": []}`。",
        "",
    ]
    if mentions:
        lines += [
            "## 材料里出现过的时间点（核对用）",
            "下面是从材料里机械抽出来的时间点。请**逐条过一遍**，确认每个时间点背后"
            "有没有需要做的事 —— 有就输出待办，确实只是资讯的可以跳过，"
            "但**不要因为清单没列全就漏掉材料里别的事项**。",
        ]
        lines += [f"- {m}" for m in mentions]
        lines.append("")
    lines += ["## 材料正文", body]
    return "\n".join(lines)


def build_verify_prompt(*, source_name: str, doc_date: str, body_text: str,
                        max_chars: int, candidates: list["_Cluster"]) -> str:
    lines = [
        "## 任务",
        "下面是同一份材料经过**多路独立抽取**得到的候选待办清单，"
        "每条后面 `[n/m]` 表示 m 路里有 n 路抽到了它。",
        "请你**对照材料原文**逐条复核，输出**最终**清单。",
        "",
        "## 复核规则（逐条判）",
        "1. **日期绑错动作**的：按材料里那句话的谓语改正"
        "（「将于X举行」→ 那天是**参加**；「X前报名」→ 才是**报名**）。改不了的删掉。",
        "2. 材料里其实**没有日期**支撑的 → 删掉（不许因为候选里有就留着）。",
        "3. 只是资讯/背景/已经做完的事 → 删掉。",
        "4. 多路结果**互相矛盾**时（同一件事日期不同），以**材料原文**为准，只留正确的那条。",
        "5. 候选里**明显漏掉**的、材料里有明确日期又需要当事人做的事 → 补上。",
        "6. 保留材料给出的**选项**，不要替当事人挑一个。",
        "7. 票数低不等于错，票数高不等于对 —— 判断依据是材料原文。",
        "8. `evidence` 只抄包含该日期、并描述那件事的**那一句**。",
        "",
        "## 材料信息",
        f"- 来源文件：{friendly_source(source_name)}",
        f"- 材料日期：{doc_date}",
        "",
        "## 候选清单",
    ]
    if candidates:
        for i, c in enumerate(candidates, 1):
            span = c.start if c.start == c.end else f"{c.start} → {c.end}"
            lines.append(f"{i}. [{c.votes}/{c.passes}] {span}  {c.task}")
            if c.evidence:
                lines.append(f"   依据：{' '.join(c.evidence.split())[:180]}")
    else:
        lines.append("（候选为空 —— 但材料里可能确实有待办，请自己再找一遍）")
    lines += [
        "",
        "## 材料正文",
        body_text.strip()[:max_chars],
        "",
        "只输出 JSON：`{\"todos\": [{\"task\": \"…\", \"start\": \"YYYY-MM-DD\", "
        "\"end\": \"YYYY-MM-DD\", \"evidence\": \"…\"}]}`；没有就 `{\"todos\": []}`。",
    ]
    return "\n".join(lines)


# ================================================================ 时间词


# 材料里有没有「时间词」。没有任何时间词时，模型给出的日期一定是编的 —— 全部丢掉。
_TEMPORAL_RE = re.compile(
    r"(\d+\s*[年月日号点时分])|([年月日]\s*\d)|(\d{1,2}\s*[:：]\s*\d{2})"
    r"|(今天|今日|明天|明日|后天|大后天|昨天|前天|今晚|明晚)"
    r"|(本周|这周|上周|下周|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天])"
    r"|(本月|这个月|上个月|下个月|月底|月初|年初|年底|季度|第[一二三四五六七八九十]+周)"
    r"|(\d{4}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2})"
    r"|(\d+\s*天\s*(内|后))|(截止)"
)


def has_temporal_reference(text: str) -> bool:
    """材料里有没有「时间词」。没有任何时间词时，模型给出的日期一定是编的。"""
    text = text or ""
    if _TEMPORAL_RE.search(text):
        return True
    # 手机截图里极常见的「10.31」「10/31」写法（点号在小数里也合法，所以逐个验月/日）
    return any(_md_valid(m.group(0)) for m in _MD_RE.finditer(text))


# 「月.日」「月/日」：注意别把小数（如 3.14）当成日期
_MD_RE = re.compile(r"(?<![\d.])(\d{1,2})\s*[./]\s*(\d{1,2})(?![\d])")
# 后面紧跟计量单位的多半是小数，不是日期（"12.5 元"）—— 只用来给清单去噪，
# 护栏 has_temporal_reference() 不套这条：漏掉真日期会把整条待办丢掉，比偶尔放过更糟。
_MD_UNIT = re.compile(r"\s*(元|块|%|％|倍|万|亿|公里|km|kg|克|米|分|秒|个|人|次|度|℃|V|A|W)")


def _md_valid(token: str) -> bool:
    m = re.fullmatch(r"\s*(\d{1,2})\s*[./]\s*(\d{1,2})\s*", token)
    if not m:
        return False
    return 1 <= int(m.group(1)) <= 12 and 1 <= int(m.group(2)) <= 31


# 抽取「时间点」当核对表喂给模型：小模型最容易漏掉跨日事项，
# 把材料里出现过的时间点列成清单，它照着逐条过，召回明显更稳。
_RANGE_RE = re.compile(
    r"(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?\s*(?:至|到|~|～|-|—|－|–)\s*"
    r"(?:(\d{1,2})\s*月\s*)?(\d{1,2})\s*[日号]")
_DATE_RE = re.compile(
    r"(\d{4}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2})"
    r"|(\d{1,2}\s*月\s*\d{1,2}\s*[日号]?)"
    r"|((?:下|本|这|上)?\s*(?:个)?\s*周[一二三四五六日天])"
    r"|((?:下|本|这|上)\s*个?\s*月(?:底|初|中)?)"
    r"|(\d{1,2}\s*月(?:底|初|中))"
    r"|(第\s*\d{1,2}\s*周)")
_TAIL_STOP = re.compile(r"[。；;！!？?\n]")


def time_mentions(text: str, limit: int = 12) -> list[str]:
    """按出现顺序列出材料里的时间点，每条带一点点后文，供模型核对。"""
    text = text or ""
    spans: list[tuple[int, int, str]] = []
    for m in _RANGE_RE.finditer(text):
        spans.append((m.start(), m.end(), m.group(0).strip()))
    for m in _DATE_RE.finditer(text):
        if any(s <= m.start() < e for s, e, _ in spans):
            continue
        spans.append((m.start(), m.end(), m.group(0).strip()))
    for m in _MD_RE.finditer(text):
        if not _md_valid(m.group(0)):
            continue
        if _MD_UNIT.match(text[m.end():m.end() + 3]):
            continue
        if any(s <= m.start() < e for s, e, _ in spans):
            continue
        spans.append((m.start(), m.end(), m.group(0).strip()))
    spans.sort(key=lambda x: x[0])
    out: list[str] = []
    seen: set[str] = set()
    for start, end, hit in spans:
        tail = text[end:end + 14]
        cut = _TAIL_STOP.search(tail)
        if cut:
            tail = tail[:cut.start()]
        tail = re.sub(r"[\s\d]*$", "", tail)      # 别把 "10 月 1" 这种半个数字带出来
        item = f"{hit}{tail}".strip()
        key = re.sub(r"\s+", "", hit)
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
        if len(out) >= limit:
            break
    return out


# ================================================================ 调用模型


def _post(cfg: Config, payload: dict) -> dict:
    url = f"http://{cfg.models.llm_host}:{cfg.models.llm_port}/v1/chat/completions"
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=cfg.models.llm_timeout) as r:
        return json.loads(r.read())


_REMOTE_CACHE: dict[str, object] = {}
_CHAIN_DECIDED = False


def _remote(cfg: Config):
    """按配置缓存一个 RemoteLLM（远程免费模型，带退避重试与降级链）。

    链序由魔粒余额决定（用户 2026-10-18 要求「有魔粒就用 V4-Pro」）：
    余额够就优先 ModelScope，不够/查不到就只用 ¥0 的 ZAI 与 SiliconFlow。
    每个进程只决策一次（余额查询不该每次调用都发）。
    """
    global _CHAIN_DECIDED
    from archiver import llm_remote

    key = "|".join(cfg.models.llm_remote_chain)
    if key not in _REMOTE_CACHE:
        chain = []
        for item in cfg.models.llm_remote_chain:
            provider, _, model = str(item).partition(":")
            chain.append((provider.strip(), model.strip()))
        chain, note = llm_remote.choose_chain(chain, prefer_paid=True)
        _REMOTE_CACHE[key] = llm_remote.RemoteLLM(
            chain=chain,
            timeout=cfg.models.llm_remote_timeout,
            min_interval=cfg.models.llm_min_interval,
        )
        _REMOTE_CACHE[key + "#note"] = note
        _CHAIN_DECIDED = True
    return _REMOTE_CACHE[key]


def remote_note(cfg: Config) -> str:
    """给 CLI 显示用的链路决策说明（空串表示还没决策）。"""
    key = "|".join(cfg.models.llm_remote_chain)
    return str(_REMOTE_CACHE.get(key + "#note") or "")


def _extract_json(text: str) -> dict | None:
    text = re.sub(r"```(?:json)?|```", "", text or "").strip()
    text = re.sub(r"^.*?<｜end▁of▁thinking｜>", "", text, flags=re.S).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    depth, start = 0, None
    for i, ch in enumerate(text):
        if ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and start is not None:
                try:
                    return json.loads(text[start:i + 1])
                except json.JSONDecodeError:
                    start = None
    return None


_DATE_ONLY = re.compile(r"^(\d{4})-(\d{1,2})-(\d{1,2})$")


def _norm_date(value: object) -> str | None:
    """把模型给的日期收成 YYYY-MM-DD；收不了返回 None（空字符串也算 None）。"""
    if value is None:
        return None
    s = str(value).strip()
    if not s:
        return None
    m = _DATE_ONLY.match(s)
    if not m:
        # 兼容 2026/10/14、2026.10.14 这类写法
        s2 = s.replace("/", "-").replace(".", "-")
        m = _DATE_ONLY.match(s2)
        if not m:
            return None
    try:
        d = _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None
    return d.isoformat()


def _chat(cfg: Config, *, system: str, user: str, temperature: float,
          max_tokens: int | None = None) -> tuple[dict | None, str, str]:
    """返回 (解析出的 JSON, 原始文本, 错误)。"""
    if cfg.models.llm_is_remote:
        # 远程免费模型：退避重试 + 降级链都在 llm_remote 里，这里只管转换。
        # 注意它优先用 json_schema：实测 json_object 在有思考链的模型上会把
        # max_tokens 烧光、content 返回空。
        data, text, err = _remote(cfg).chat_json(
            user, system=system, schema=SCHEMA, temperature=temperature,
            max_tokens=max_tokens or cfg.models.llm_remote_max_tokens)
        if data is None:
            return None, "", err
        return data, text, ""

    base = {
        "model": cfg.models.llm_alias,
        "temperature": temperature,
        "max_tokens": max_tokens or cfg.models.llm_max_tokens,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user}],
    }
    attempts = [
        ("json_schema/新式", {**base, "response_format": {
            "type": "json_schema",
            "json_schema": {"name": "todos", "strict": True, "schema": SCHEMA}}}),
        ("json_schema/旧式", {**base, "response_format": {
            "type": "json_schema", "schema": SCHEMA}}),
        ("无约束", dict(base)),
    ]
    last_err = ""
    for label, payload in attempts:
        try:
            resp = _post(cfg, payload)
        except urllib.error.HTTPError as exc:
            last_err = f"{label}: HTTP {exc.code} {exc.read()[:160]!r}"
            continue
        except Exception as exc:  # noqa: BLE001
            last_err = f"{label}: {exc}"
            continue
        text = ((resp.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        data = _extract_json(text)
        if not isinstance(data, dict):
            last_err = f"{label}: 输出不是 JSON"
            continue
        return data, text, ""
    return None, "", last_err


# ============================================================ 日期↔依据绑定


# 相对说法里「偏移是固定的」那批：拿材料日期直接加减就能算死
_REL_DAY_WORDS = {
    "大后天": 3, "今天": 0, "今日": 0, "今晚": 0, "本日": 0, "当晚": 0,
    "明天": 1, "明日": 1, "明晚": 1, "后天": 2, "后日": 2,
    "昨天": -1, "昨日": -1, "昨晚": -1, "前天": -2, "前日": -2,
}
_REL_DAY_RE = re.compile(
    "|".join(sorted((re.escape(k) for k in _REL_DAY_WORDS), key=len, reverse=True)))
_WEEKDAY_CHARS = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6,
                  "日": 7, "天": 7}
# 「本周日 / 下周三 / 上星期五」：前缀那个字和「周」是同一个字，所以「本周日」=
# pre=本 + wk=周 + d=日，这样一个正则就能吃下（否则会把「本周日」拆成
# 「本周」+「周日」两种读法）。
_WEEK_POINT_RE = re.compile(
    r"(?P<pre>本|这|当|上|下|前|后)(?:个)?(?P<wk>周|星期|礼拜)(?P<d>[一二三四五六日天])"
    r"|(?P<wk2>周|星期|礼拜)(?P<bd>[一二三四五六日天])"
)
# 光说「本周 / 下周 / 这个周」不给星期几 —— 那是一整周的范围，只能核对不能换算
_WEEK_RANGE_RE = re.compile(
    r"(?P<pre>本|这|当|上|下|前|后)(?:个)?周(?![一二三四五六日天末未])")
_WEEKEND_RE = re.compile(r"(?P<pre>本|这|当|上|下|前|后)?(?:周末|周未)")
_MONTH_EDGE_RE = re.compile(r"(?P<pre>下|上|本|这|当)?(?:个)?(?P<edge>月底|月末|月初)")
_DAYS_LATER_RE = re.compile(r"(?P<n>\d{1,3})(?:个)?(?:天|日)(?:之)?(?P<dir>后|前|内)")


def _week_date(base: _dt.date, pre: str, wd: int) -> _dt.date:
    """base 所在那一周（周一开始）的第 wd 天；pre 是 上/下 时前后挪一周。"""
    monday = base - _dt.timedelta(days=base.isoweekday() - 1)
    d = monday + _dt.timedelta(days=wd - 1)
    if pre in ("上", "前"):
        d -= _dt.timedelta(days=7)
    elif pre in ("下", "后"):
        d += _dt.timedelta(days=7)
    return d


def _month_edge(base: _dt.date, pre: str, edge: str) -> _dt.date:
    m = base.month + {"下": 1, "上": -1}.get(pre, 0)
    y = base.year + (m - 1) // 12
    m = (m - 1) % 12 + 1
    if edge == "月初":
        return _dt.date(y, m, 1)
    nxt = _dt.date(y + (m == 12), 1 if m == 12 else m + 1, 1)
    return nxt - _dt.timedelta(days=1)


def resolve_date_clues(text: str,
                       base: _dt.date | None) -> tuple[set[str], dict[str, str]]:
    """依据那句话里的相对说法，各能指到哪些日子。

    返回 `(所有候选, 能算死的那些→原文说法)`。第二个字典**只有一个键**时才允许
    替模型改日期：「本周日」算得出唯一一天，「周五」和「本周一到周三」都算不出。
    """
    t = _tight(text or "")
    cands: set[str] = set()
    exact: dict[str, str] = {}
    if not t or base is None:
        return cands, exact

    def put(d: _dt.date, clue: str, sure: bool = True) -> None:
        iso = d.isoformat()
        cands.add(iso)
        if sure and iso not in exact:
            exact[iso] = clue

    for m in _REL_DAY_RE.finditer(t):
        put(base + _dt.timedelta(days=_REL_DAY_WORDS[m.group(0)]), m.group(0))
    for m in _WEEK_POINT_RE.finditer(t):
        wd = _WEEKDAY_CHARS[m.group("d") or m.group("bd")]
        pre = m.group("pre") or ""
        if pre:
            put(_week_date(base, pre, wd), m.group(0))
        else:
            # 光杆「周五」：本周的那个、或者下周的那个，两种读法都说得通 → 只核对不换算
            put(_week_date(base, "", wd), m.group(0), sure=False)
            put(_week_date(base, "下", wd), m.group(0), sure=False)
    for m in _WEEK_RANGE_RE.finditer(t):
        monday = _week_date(base, m.group("pre"), 1)
        for i in range(7):
            put(monday + _dt.timedelta(days=i), m.group(0), sure=False)
    for m in _WEEKEND_RE.finditer(t):
        saturday = _week_date(base, m.group("pre") or "", 6)
        put(saturday, m.group(0), sure=False)
        put(saturday + _dt.timedelta(days=1), m.group(0), sure=False)
    for m in _MONTH_EDGE_RE.finditer(t):
        put(_month_edge(base, m.group("pre") or "", m.group("edge")), m.group(0))
    for m in _DAYS_LATER_RE.finditer(t):
        n = int(m.group("n"))
        if m.group("dir") == "后":
            put(base + _dt.timedelta(days=n), m.group(0))
        elif m.group("dir") == "前":
            put(base - _dt.timedelta(days=n), m.group(0))
        else:                                  # 「三天内」是一段范围
            for i in range(n + 1):
                put(base + _dt.timedelta(days=i), m.group(0), sure=False)
    return cands, exact


def _date_in_evidence(iso: str, ev_tight: str) -> bool:
    """这个日期在「依据那句话」里有没有落点。

    除了 `_date_forms` 的那些写法，还认「10月15日至18日」这种后半截省月份的写法
    （条件是同一句里出现过「10月」），以及 `14:00-15日` 这种只留日的区间尾巴。
    """
    if any(f in ev_tight for f in _date_forms(iso)):
        return True
    try:
        d = _dt.date.fromisoformat(iso)
    except (TypeError, ValueError):
        return False
    if f"{d.month}月" not in ev_tight:
        return False
    return bool(re.search(rf"(?<!\d){d.day}[日号](?!\d)", ev_tight))


def _bind_entry(e: TodoEntry, base: _dt.date | None) -> bool:
    """把日期钉死在它自己那条依据上；钉不住就把这条丢掉。

    之前只分开查「依据在不在材料里」和「日期在不在材料里」，两条各自都成立、但**互相没关系**：
    实测有过「参加」那条填的是报名截止日（那个日期只在报名那句里出现过），而它自己抄的
    依据是一句只写了「本周日开课」的话 —— 换算出来根本不是那一天。
    现在：日期要么写在这句话里，要么能由这句话里的相对说法算出来；
    算得出的唯一值还会替模型换算过来（4B 自己算不准，代码算得准）。
    """
    if base is None or not e.evidence:
        return True
    ev = _tight(e.evidence)
    cands: set[str] = set()
    exact: dict[str, str] = {}
    if not _date_in_evidence(e.start, ev):
        cands, exact = resolve_date_clues(e.evidence, base)
        if e.start in cands:
            pass
        elif len(exact) == 1:
            new, clue = next(iter(exact.items()))
            old = e.start
            e.date_before = old
            # 相对说法指的是**某一天**，模型那种跨日区间一起收拢到这天
            e.start = e.end = new
            e.redated = f"{e.task}：{old}→{new}（依据「{clue}」换算）"
        else:
            return False
    if e.end != e.start and not _date_in_evidence(e.end, ev):
        if not cands:
            cands, _ = resolve_date_clues(e.evidence, base)
        if e.end not in cands:
            return False
    return True


def _normalize(data: dict, cfg: Config, *, raw: str, model: str,
               source_text: str = "", source_pass: str = "主提示词",
               passes: int = 1, doc_date: str = "") -> TodoResult:
    res = TodoResult(raw=raw, model=model or cfg.models.llm_alias, passes=passes)
    # 代码级护栏：材料里连一个时间词都没有，那模型给的任何日期都是编的
    anchored = has_temporal_reference(source_text) if source_text else True
    base = _norm_date(doc_date)
    base_d = _dt.date.fromisoformat(base) if base else None
    today = _dt.date.today()
    lo = today - _dt.timedelta(days=cfg.todos.past_grace_days)
    hi = today + _dt.timedelta(days=cfg.todos.future_limit_days)
    # 状态通知（「已完成 / 已取消」）跟待办分开收：它们要落到**已有的**待办上，
    # 自己不是待办（没有新动作、也没有日期）
    for key, state in (("done", "done"), ("cancelled", "cancelled")):
        for it in (data.get(key) or []):
            if not isinstance(it, dict):
                continue
            ct = str(it.get("task") or "").strip()
            if not ct:
                continue
            res.closes.append(CloseSignal(
                task=ct, state=state,
                evidence=str(it.get("evidence") or "").strip()[:200]))

    # 代码自己再扫一遍正文兜底：模型那两栏是可选输出，而这类通知很规整，扫一遍更稳。
    # 与模型已给的说法「像」的就不再收第二遍（同一件事常被写成「报名秋季联赛」/「秋季联赛报名」）
    for c in scan_closes(source_text):
        if any(c.state == e.state and close_score(c.task, e.task) >= CLOSE_MATCH_MIN
               for e in res.closes):
            continue
        res.closes.append(c)

    raw_items = data.get("todos")
    if not isinstance(raw_items, list):
        raw_items = []
    for it in raw_items:
        if not isinstance(it, dict):
            continue
        task = str(it.get("task") or "").strip()
        if not task:
            continue
        s = _norm_date(it.get("start"))
        e = _norm_date(it.get("end")) or s
        if not s:
            res.dropped_no_date += 1
            continue
        if not anchored:
            res.dropped_no_anchor += 1
            continue
        if e < s:
            s, e = e, s
        try:
            d0, d1 = _dt.date.fromisoformat(s), _dt.date.fromisoformat(e)
        except ValueError:
            res.dropped_insane += 1
            continue
        if d0 < lo or d1 > hi or (d1 - d0).days + 1 > cfg.todos.max_span_days:
            res.dropped_insane += 1
            continue
        entry = TodoEntry(task=task[:120], start=s, end=e,
                          evidence=str(it.get("evidence") or "").strip()[:200],
                          source_pass=source_pass)
        if not _bind_entry(entry, base_d):
            res.dropped_unbound += 1
            continue
        if entry.redated:
            res.date_fixed.append(entry.redated)
        res.entries.append(entry)
    return res


# ================================================================ 一致度聚类


def _norm_text(s: str) -> str:
    return re.sub(r"[\s\W_]+", "", str(s or ""), flags=re.UNICODE)


def _similar(a: str, b: str) -> float:
    na, nb = _norm_text(a), _norm_text(b)
    if not na or not nb:
        return 0.0
    if na in nb or nb in na:
        return 1.0
    return difflib.SequenceMatcher(None, na, nb).ratio()


def _overlap(a0: str, a1: str, b0: str, b1: str) -> bool:
    return not (a1 < b0 or b1 < a0)


@dataclass
class _Cluster:
    """多路抽取里被认为是「同一件事」的一组结果。"""
    members: list[tuple[int, TodoEntry]] = field(default_factory=list)
    passes: int = 1

    # ---- 代表值：取出现次数最多的说法；并列时取最早那一路（主提示词） ----
    def _pick(self, key) -> object:
        counts: dict[object, int] = {}
        first: dict[object, int] = {}
        for pi, e in self.members:
            k = key(e)
            counts[k] = counts.get(k, 0) + 1
            first.setdefault(k, pi)
        return max(counts, key=lambda k: (counts[k], -first[k]))

    @property
    def task(self) -> str:
        """同一簇里的代表说法。

        先看「谁包含了更多别的说法」：多路结果常出现一版缩写、一版完整
        （`报名` vs `汇总报名名单`），此时取**信息最全**的那条，而不是票数多的缩写版；
        包含关系打平再比票数，还打平取最早那一路（温度 0 的主提示词）。
        """
        texts = [e.task for _, e in self.members]
        uniq: list[str] = []
        for t in texts:
            if t not in uniq:
                uniq.append(t)

        def contains_score(t: str) -> int:
            nt = _norm_text(t)
            return sum(1 for u in uniq if u != t and _norm_text(u) in nt)

        def first_pass(t: str) -> int:
            return min(pi for pi, e in self.members if e.task == t)

        # 先按「点名了没有」排：空泛标题（换个活动也成立）永远不该当代表，
        # 否则它票多就把同簇里那条说清名字的说给盖掉。
        return max(uniq, key=lambda t: (not _blanket_task(t), contains_score(t),
                                        texts.count(t), len(t), -first_pass(t)))

    @property
    def start(self) -> str:
        return str(self._pick(lambda e: e.start))

    @property
    def end(self) -> str:
        return str(self._pick(lambda e: e.end))

    @property
    def days(self) -> set[str]:
        """这一簇碰过的日子，**含被护栏换算掉的旧日期**。

        护栏按「本周日」把日期搬走之后，同一件事的两条说法就不在同一天了；
        收尾只看现日期就认不出它们是孪生，会让一个字都点不出活动的空泛标题活下来。
        """
        out: set[str] = set()
        for _, e in self.members:
            out.update(d for d in (e.start, e.end, e.date_before) if d)
        return out

    @property
    def evidence(self) -> str:
        for pi, e in sorted(self.members, key=lambda m: m[0]):
            if e.evidence:
                return e.evidence
        return ""

    @property
    def redated(self) -> str:
        """这一簇里有没有成员是被护栏换算过日期的（有就透出去，让事项文件写明来路）。"""
        for _, e in sorted(self.members, key=lambda m: m[0]):
            if e.redated:
                return e.redated
        return ""

    @property
    def votes(self) -> int:
        return len({pi for pi, _ in self.members})

    @property
    def variants(self) -> list[str]:
        """同一簇里出现过的不同说法（去重、保持顺序）。多于一个说明模型没想稳。"""
        out: list[str] = []
        for _, e in self.members:
            if e.task not in out:
                out.append(e.task)
        return out

    def entry(self) -> TodoEntry:
        vs = self.variants
        return TodoEntry(task=self.task, start=self.start, end=self.end,
                         evidence=self.evidence, votes=self.votes,
                         source_pass="多路一致" if self.votes > 1 else self.members[0][1].source_pass,
                         variants=vs if len(vs) > 1 else [],
                         redated=self.redated)


def cluster_drafts(drafts: list[TodoResult], threshold: float) -> list[_Cluster]:
    clusters: list[_Cluster] = []
    total = len(drafts)
    for pi, d in enumerate(drafts):
        for e in d.entries:
            for c in clusters:
                if any(mpi == pi for mpi, _ in c.members):
                    continue          # 同一路里的两条不合并
                if not _overlap(c.start, c.end, e.start, e.end):
                    continue
                # 与簇内**任一**成员够像就并进来（单链接聚类）。
                # 只跟"代表"比会因为先来后到把同一件事拆成两簇：
                # 「报名」→簇代表，之后「汇总报名名单」跟代表像但跟别的条目不像，就分家了。
                if any(_similar(m.task, e.task) >= threshold for _, m in c.members):
                    c.members.append((pi, e))
                    break
            else:
                clusters.append(_Cluster(members=[(pi, e)], passes=total))
    for c in clusters:
        c.passes = total
    # 票多的排前面，方便复核时先看
    clusters.sort(key=lambda c: (-c.votes, c.start))
    return clusters


# 空泛标题：只说「参加某日的讲座/培训」而不点名是哪一场，换个活动也成立。
# 实测采样路（温度 0.5）最爱吐这种 —— 一份多场活动的清单被写成几条「参加某日的讲座」。
_BLANKET_RE = re.compile(
    r"^(?:参加|出席|观看|收听|听|报名|申请|提交|缴纳|交)\s*"
    r"(?:\d{1,2}\s*月\s*\d{1,2}\s*[日号]?|今天|明天|后天|本周[一二三四五六日天]?)?\s*的?\s*"
    r"(?:讲座|培训|课程|活动|会议|比赛|考试|分享会)$"
)
# 栏目标题/弱标题写法：「参加某日的××、××、××等专场讲座」「参加某日××专场讲座」
_BLANKET_SERIES_RE = re.compile(
    r"等\s*(?:专场)?\s*(?:讲座|培训|课程|活动|分享会)"
    r"|专场\s*(?:讲座|培训|课程|活动|分享会)"
)


def _blanket_task(task: str) -> bool:
    """是不是「换个活动也成立」的空泛/系列标题（「参加课程」「报名讲座」这种）。"""
    t = _norm_text(task)
    return bool(_BLANKET_RE.match(t)) or bool(_BLANKET_SERIES_RE.search(t))


# 动词分组：只有说同一类动作的空泛/具体标题才允许互相并条。
# 「报名课程」和「参加X讲座」同日也不是一件事，并了就把一条真待办吃掉了。
_VERB_GROUPS = {"参加": "到场", "出席": "到场", "观看": "到场", "收听": "到场", "听": "到场",
                "报名": "报名", "申请": "报名",
                "提交": "交付", "缴纳": "交付", "交": "交付"}
_VERB_RE = re.compile(r"^(参加|出席|观看|收听|报名|申请|提交|缴纳|听|交)")


def _verb_group(task: str) -> str:
    m = _VERB_RE.match(_norm_text(task))
    return _VERB_GROUPS.get(m.group(1), "") if m else ""


# 「去参加某场活动」类：这类待办的措辞最容易随采样变（同一天同一场活动被叫成好几个名字），
# 而「提交/报名/交费」这类动词措辞稳定，不能拿这条规则去误伤。
_ATTEND_RE = re.compile(r"^(?:参加|出席|观看|收听|听)")


def _attend_task(task: str) -> bool:
    return bool(_ATTEND_RE.match(_norm_text(task)))


def consolidate_clusters(clusters: list[_Cluster], threshold: float) -> list[_Cluster]:
    """聚类之后的**确定性收尾**（不花模型调用，纯代码）：

    1. 跨簇合并「说法几乎一样、日期也重叠」的两簇。`cluster_drafts` 为了不让一路的
       重复输出虚增票数，规定"同一路里的两条不合并"，于是同一路自己吐出的重复条目
       （实测同一句里 `报名X（11-03）` 和 `报名X（11-03→11-04）` 会并排躺成两条）。
       这里补一刀。票数按**路号的并集**重算，合并不会让票数虚高。
    2. 空泛/系列标题（「参加某日的讲座」「…等专场讲座」）并进同一天同一类动作的
       具体那条；同一天没有点得出名字的孪生（说明材料本身就没给名字）就原样留着。
    3. 丢掉「同一天、同为参加类、只有 1 路抽到」的落单说法。实测同一场活动会被不同路
       叫成两个不同的名字（一个用主题口号、一个用副标题），落单的那条是采样噪声：
       同一天已经有 ≥2 路一致的参加类条目时，把 1 票的丢掉。只对参加类动手，
       提交/报名这类措辞稳定的条目不受影响。

    只动「同一件事被说成好几条」的情形，不合并日期不重叠的条目。
    """
    merged: list[_Cluster] = []
    for c in sorted(clusters, key=lambda c: (-c.votes, c.start, c.task)):
        for m in merged:
            if _overlap(m.start, m.end, c.start, c.end) and \
                    _similar(m.task, c.task) >= threshold:
                m.members.extend(c.members)
                break
        else:
            merged.append(_Cluster(members=list(c.members), passes=c.passes))

    # 第 1 刀：空泛/系列标题（「参加课程」「…等专场讲座」这种换个活动也成立的说法）。
    # 同一天、同一类动作已经有一条点得出名字的 → 把它**并进那条**（名字用点得出名字的，
    # 日期与票数按成员并集走多数），而不是整条丢掉 —— 丢掉会把那几路的票一起带走。
    consumed: set[int] = set()
    for c in merged:
        if not _blanket_task(c.task):
            continue
        cg = _verb_group(c.task)
        partners = [o for o in merged if o is not c and id(o) not in consumed
                    and not _blanket_task(o.task)
                    and (not cg or _verb_group(o.task) == cg)
                    and (_overlap(c.start, c.end, o.start, o.end) or (c.days & o.days))]
        if partners:
            min(partners, key=lambda o: (-o.votes, o.start)).members.extend(c.members)
            consumed.add(id(c))
    kept1 = [c for c in merged if id(c) not in consumed]
    # 第 2 刀：同一天只剩 1 票的落单「参加」说法。
    # 必须拿**第 1 刀之后还活着的**来比：否则空泛的那条（票多）和具体的那条（票少）
    # 会互相把对方判掉，同一天整组条目一起消失（实测活动清单从 7 场掉成 4 场）。
    # 比日子时连「被护栏换算掉的旧日期」一起算（c.days），否则搬过家的认不出是孪生。
    keep = [c for c in kept1
            if not (c.votes == 1 and _attend_task(c.task) and any(
                o is not c and o.votes >= 2 and _attend_task(o.task)
                and (o.start == c.start or (c.days & o.days)) for o in kept1))]
    keep.sort(key=lambda c: (-c.votes, c.start))
    return keep


# ================================================================ 状态通知 → 落到已有待办


# 匹配下限：低于它就认为「通知里说的这件事，手上没有对应的待办」，不关任何东西。
# 宁可留一条已经取消的事在列表里（你一眼能看到），也不要凭空关掉一条真待办。
CLOSE_MATCH_MIN = 0.55


def _bi_grams(text: str) -> set[str]:
    """中文按二字片段切开 —— 没空格可依，用字片段算覆盖最稳。

    统一转小写：通知里写「pdf」、待办名里写「PDF」是同一件事，别因为大小写漏配。
    """
    t = _norm_text(text).lower()
    if len(t) < 2:
        return {t} if t else set()
    return {t[i:i + 2] for i in range(len(t) - 1)}


def _verbs_in(text: str) -> set[str]:
    """文本里出现过的动作分组（不要求出现在句首 —— 通知里动词常在中间）。"""
    t = _norm_text(text)
    return {g for v, g in _VERB_GROUPS.items() if v in t}


# 状态通知的机械识别词。**故意不走模型**：这类说法很规整，代码扫一遍就够；
# 而在这台机器上，往提示词里加一段新规则会连带弄坏别的材料的抽取
# （实测加完「状态通知」那节，另一份材料里的可选组别就整段丢了）。
_DONE_WORDS = ("已经完成", "已完成", "已完成报名", "已办", "办完了", "已报名",
               "已结束", "已经结束", "搞定了", "已处理")
_CANCEL_WORDS = ("已取消", "取消", "停办", "不办了", "不举行了", "已作废", "撤销了")
# 「恢复」放在最前面判：一句里既有「取消」又有「恢复」时（「先取消、现恢复」），
# 那是在叫回来，不是在取消
_REOPEN_WORDS = ("恢复举办", "重新举办", "重新开课", "重新开始", "恢复", "复课",
                 "补办", "照常举行", "如期举行", "重新安排", "改回来了")


def scan_closes(text: str) -> list[CloseSignal]:
    """从材料正文里机械找出「已完成 / 已取消 / 又恢复」这类状态通知。

    按句切开逐句看：命中状态词就把那句去掉状态词之后剩下的部分当成「被通知的那件事」，
    交给 `match_close` 去落到已有待办上。太短（不足 4 个字）的不当成一件事，避免噪声。
    """
    out: list[CloseSignal] = []
    for chunk in re.split(r"[。！？；\n]", text or ""):
        s = chunk.strip()
        if len(_norm_text(s)) < 4:
            continue
        groups = ((_REOPEN_WORDS, "reopen"), (_DONE_WORDS, "done"), (_CANCEL_WORDS, "cancelled"))
        for words, state in groups:
            hit = next((w for w in words if w in s), "")
            if not hit:
                continue
            task = s.replace(hit, "")
            # 状态词被摘掉后常留下游离副词：「摄影培训**已**恢复举办」→ 要还原成「摄影培训」
            task = re.sub(r"^[\s　，,、:：]*(?:现已|已经|重新|又|已|现)+", "", task)
            task = re.sub(r"(?:现已|已经|已|现|又|了)+[\s　，,、:：。]*$", "", task)
            task = task.strip("　 ，,、:：的")
            if len(_norm_text(task)) >= 4:
                out.append(CloseSignal(task=task, state=state, evidence=s[:200]))
            break
    return out


def close_score(signal_task: str, todo_task: str) -> float:
    """通知里的说法与某条待办有多像（0~1）。

    三个口径合起来看：
    ① 通知的字片段有多大比例能在待办里找到（中文没空格，按二字片段算覆盖最稳）；
    ② 整体相似度；③ **动词是否同类** —— 通知说「报名已完成」时，同一份材料里往往
    还有一条「参加…」，只按字符串两者都很像，靠动词（报名 vs 参加）才分得开。
    """
    base = _similar(signal_task, todo_task)
    if base >= 1.0:
        return 1.0
    a, b = _bi_grams(signal_task), _bi_grams(todo_task)
    cover = len(a & b) / len(a) if a else 0.0
    score = max(cover, base)
    va, vb = _verbs_in(signal_task), _verbs_in(todo_task)
    if va and vb:
        score += 0.10 if (va & vb) else -0.10
    return max(0.0, min(1.0, score))


def match_close(signal: "CloseSignal", candidates: list) -> tuple | None:
    """把一条关闭指令落到最像的那条 active 待办上；对不上就返回 None。"""
    best = None
    best_score = 0.0
    for row in candidates:
        task = row["task"] if not isinstance(row, dict) else row.get("task", "")
        score = close_score(signal.task, task or "")
        if score > best_score:
            best, best_score = row, score
    if best is None or best_score < CLOSE_MATCH_MIN:
        return None
    return best, best_score


# ================================================================ 对外入口


def extract_once(*, source_name: str, doc_date: str, body_text: str, cfg: Config,
                 today: str | None = None, temperature: float = 0.0,
                 variant: str = "main", source_pass: str = "主提示词") -> TodoResult:
    """只问模型一次（快；准确率优先时用 extract()）。"""
    today = today or _dt.date.today().isoformat()
    doc_date = (doc_date or today)[:10]
    user = build_prompt(source_name=source_name, doc_date=doc_date, today=today,
                        body_text=body_text or "",
                        max_chars=cfg.models.max_text_chars, variant=variant)
    data, raw, err = _chat(cfg, system=SYSTEM_PROMPT, user=user, temperature=temperature)
    if data is None:
        return TodoResult(error=err)
    return _normalize(data, cfg, raw=raw, model="", source_text=body_text,
                      source_pass=source_pass, passes=1, doc_date=doc_date)


def _verify(*, source_name: str, doc_date: str, body_text: str, cfg: Config,
            clusters: list[_Cluster]) -> tuple[list[TodoEntry], str, str]:
    user = build_verify_prompt(source_name=source_name, doc_date=doc_date,
                               body_text=body_text,
                               max_chars=cfg.models.max_text_chars,
                               candidates=clusters)
    data, raw, err = _chat(cfg, system=SYSTEM_PROMPT, user=user,
                           temperature=0.0,
                           max_tokens=cfg.models.llm_max_tokens + 400)
    if data is None:
        return [], raw, err
    res = _normalize(data, cfg, raw=raw, model="", source_text=body_text,
                     source_pass="复核", passes=len(clusters[0].members) if clusters else 1,
                     doc_date=doc_date)
    # 把复核结果对回候选，继承票数（复核新增的记 0 票，标注来自复核补漏）
    out: list[TodoEntry] = []
    for e in res.entries:
        votes = 0
        for c in clusters:
            if _overlap(c.start, c.end, e.start, e.end) and \
                    _similar(c.task, e.task) >= cfg.todos.merge_threshold:
                votes = max(votes, c.votes)
        e.votes = votes
        if votes == 0:
            e.source_pass = "复核补漏"
        out.append(e)
    return out, raw, res.error


def _tight(text: str) -> str:
    """只去空白、**保留标点** —— 日期比对要用它：`10.31` 的点是有意义的。"""
    return re.sub(r"\s+", "", text or "")


def _skeleton(text: str) -> str:
    """去空白**与标点**、只留字词 —— 依据比对要用它：OCR 折行/多括号不该算不一致。"""
    return re.sub(r"[\s\W_]+", "", text or "", flags=re.UNICODE)


def _date_forms(iso: str) -> list[str]:
    """一个 ISO 日期在中文材料里可能的写法。"""
    try:
        d = _dt.date.fromisoformat(iso)
    except (TypeError, ValueError):
        return []
    return [
        iso,                              # 2026-10-31
        f"{d.year}年{d.month}月{d.day}日",
        f"{d.month}月{d.day}日",
        f"{d.month}月{d.day}号",
        f"{d.month}.{d.day}",             # 手机截图里最常见
        f"{d.month}/{d.day}",
        f"{d.year}.{d.month}.{d.day}",
    ]


def ground_entries(entries: list[TodoEntry], body_text: str) -> None:
    """确定性核对（不花一次模型调用）：

    - `evidence_grounded`：模型抄的依据是否真的能在材料里找到（防凭空编造依据）；
    - `date_literal`：这个日期是否真在材料里出现过（点号/斜杠/月日等写法都算）。

    只打标、不删除 —— 相对日期（"下周三"）换算出来的绝对日期本来就不在材料里，
    按字面删会把真待办误杀。标记写进事项文件，让人一眼看出哪条该复核。
    """
    tight = _tight(body_text)
    skeleton = _skeleton(body_text)
    for e in entries:
        # 用「骨架」（去掉空白与标点）比对：OCR 最常见的错法就是多/少一个括号、
        # 折行处重复一个标点，按字面比会大面积误报。
        ev = _norm_text(e.evidence)[:60]
        if not ev:
            e.evidence_grounded = True
        else:
            m = difflib.SequenceMatcher(None, ev, skeleton,
                                        autojunk=False).find_longest_match()
            e.evidence_grounded = m.size >= max(8, int(len(ev) * 0.8))
        forms = [f for f in _date_forms(e.start) if f in tight]
        if e.end != e.start:
            forms += [f for f in _date_forms(e.end) if f in tight]
        e.date_literal = bool(forms)


def extract(*, source_name: str, doc_date: str, body_text: str, cfg: Config,
            today: str | None = None, fast: bool = False) -> TodoResult:
    """准确率优先的抽取：多路抽取 → 一致度聚类 → 材料在手复核。"""
    today = today or _dt.date.today().isoformat()
    doc_date = (doc_date or today)[:10]
    if not (body_text or "").strip():
        return TodoResult()
    if fast:
        return extract_once(source_name=source_name, doc_date=doc_date,
                            body_text=body_text, cfg=cfg, today=today)

    # 远程大模型不需要那么多票：sample_temperature=0 时第 2..N 路是重复调用，
    # 只在弱模型上才有兜底意义。见 config.py 的 passes_remote 注释。
    passes = max(1, cfg.todos.passes_remote if cfg.models.llm_is_remote
                 else cfg.todos.passes)
    drafts: list[TodoResult] = []
    notes: list[str] = []
    # 第 1 路：温度 0，求稳
    drafts.append(extract_once(source_name=source_name, doc_date=doc_date,
                               body_text=body_text, cfg=cfg, today=today,
                               temperature=0.0, source_pass="主提示词"))
    # 第 2..N 路：带温度采样，求广（temperature=0 时多跑几路也是同一结果，没意义）
    for i in range(2, passes + 1):
        drafts.append(extract_once(source_name=source_name, doc_date=doc_date,
                                   body_text=body_text, cfg=cfg, today=today,
                                   temperature=cfg.todos.sample_temperature,
                                   source_pass=f"采样第{i}路"))
    # 换切分：先抄「时间点→谓语」再转待办，走另一条推理路径。
    # 温度跟采样路一致：实测换切分那路在 0.2 上会吐出「参加某日××专场讲座」
    # 这种拿栏目标题当活动名的弱标题，0 上不会。
    if cfg.todos.alt_prompt:
        drafts.append(extract_once(source_name=source_name, doc_date=doc_date,
                                   body_text=body_text, cfg=cfg, today=today,
                                   temperature=cfg.todos.sample_temperature,
                                   variant="pairs",
                                   source_pass="换切分"))
    errs = [d.error for d in drafts if d.error]
    # 日期↔依据绑定护栏是逐路做的，这里把它的战果汇总出来给用户看
    unbound = sum(d.dropped_unbound for d in drafts)
    fixed = [x for d in drafts for x in d.date_fixed]
    if fixed:
        notes.append("日期按依据换算：" + "；".join(fixed))
    if unbound:
        notes.append(f"{unbound} 条次日期在自己抄的依据里没有落点，已丢掉")
    raw_clusters = cluster_drafts(drafts, cfg.todos.merge_threshold)
    # 确定性收尾：合并同一路自己吐出的重复、丢掉空泛/系列标题（不花模型调用）
    clusters = consolidate_clusters(raw_clusters, cfg.todos.merge_threshold)
    notes.append(f"{len(drafts)} 路抽取 → {len(raw_clusters)} 个候选"
                 f" → 收尾后 {len(clusters)} 条"
                 if len(clusters) != len(raw_clusters)
                 else f"{len(drafts)} 路抽取 → {len(clusters)} 个候选")

    if cfg.todos.verify and clusters:
        final, vraw, verr = _verify(source_name=source_name, doc_date=doc_date,
                                    body_text=body_text, cfg=cfg, clusters=clusters)
        if verr:
            notes.append(f"复核失败（{verr}），退回候选合并结果")
            final = [c.entry() for c in clusters]
        else:
            notes.append("已复核")
    else:
        final = [c.entry() for c in clusters]

    ground_entries(final, body_text)
    shaky = [e for e in final if not e.evidence_grounded or not e.date_literal]
    if shaky:
        notes.append(f"{len(shaky)} 条确定性核对没过（见事项文件 ⚠️）")
    # 状态通知按「说法 + 状态」去重（多路会重复抽到同一件事）
    seen_close: set[tuple[str, str]] = set()
    closes: list[CloseSignal] = []
    for d in drafts:
        for c in d.closes:
            key = (c.state, _norm_text(c.task))
            if key in seen_close:
                continue
            seen_close.add(key)
            closes.append(c)
    return TodoResult(entries=final, closes=closes, error="; ".join(errs), raw="", model="",
                      passes=len(drafts), notes=notes)
