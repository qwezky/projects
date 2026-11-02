"""落盘逻辑：records/（档案）与 todos/（事项）两部分。

    <archive>/records/
        <文本原名>.<ext>                  ← 文本：原样直接放进来
        <图片原名去后缀>/                  ← 图片：一条一个打包目录
            <图片原名>.<ext>              ←   原图
            text.md                        ←   OCR 出来的文字（带少量元数据头）
    <archive>/todos/
        2026.10.14/                        ← 按「事项自己涉及的日期」建目录
            todos.md                       ←   那天有效的待办（人读）
            todos.jsonl                    ←   同上（机读）
        index.jsonl                        ← 全部待办（机读，时间段查询用）
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import re
import shutil
from pathlib import Path

from .store import Store
from .util import safe_name

DAY_DIR_RE = re.compile(r"^\d{4}\.\d{2}\.\d{2}$")


# ---------------------------------------------------------------- 通用


def unique_path(path: Path) -> Path:
    """同名时追加 -2、-3 …，绝不覆盖已有的东西。"""
    if not path.exists():
        return path
    stem, suffix, parent = path.stem, path.suffix, path.parent
    for i in range(2, 1000):
        cand = parent / f"{stem}-{i}{suffix}"
        if not cand.exists():
            return cand
    raise RuntimeError(f"无法为 {path} 找到可用文件名")


def unique_dir(path: Path) -> Path:
    if not path.exists():
        return path
    for i in range(2, 1000):
        cand = path.parent / f"{path.name}-{i}"
        if not cand.exists():
            return cand
    raise RuntimeError(f"无法为 {path} 找到可用目录名")


def transfer(src: Path, dst: Path, mode: str = "move") -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if mode == "copy":
        shutil.copy2(src, dst)
        return
    try:
        os.replace(src, dst)          # 同一文件系统内 = 原子改名
    except OSError:
        shutil.move(str(src), str(dst))


# ---------------------------------------------------------------- records/


def text_record_path(records: Path, name: str, name_max: int = 80) -> Path:
    """文本类：直接进 records/，保留原名（只做长度与非法字符清洗）。"""
    p = Path(name)
    stem = safe_name(p.stem, name_max, fallback="file")
    return unique_path(records / f"{stem}{p.suffix.lower()}")


def image_bundle_dir(records: Path, name: str, name_max: int = 60) -> Path:
    """图片：records/<名字>/ 作为打包目录。"""
    stem = safe_name(Path(name).stem, name_max, fallback="image")
    return unique_dir(records / stem)


BUNDLE_HEADER = """---
source_name: {source_name}
source_size: {size}
sha256: {sha256}
captured_or_uploaded: {doc_date}
ocr_engine: {ocr_engine}
ocr_chars: {ocr_chars}
archived_at: {archived_at}
---

# {title}

"""


def write_bundle_text(bundle: Path, *, source_name: str, size: int, sha256: str,
                      doc_date: str, ocr_engine: str, ocr_text: str,
                      extra: dict | None = None) -> Path:
    """把图 + 文字打包里的文字落成 text.md（正文就是 OCR 原文，不做改写）。"""
    path = bundle / "text.md"
    head = BUNDLE_HEADER.format(
        source_name=source_name, size=size, sha256=sha256,
        doc_date=doc_date or "", ocr_engine=ocr_engine or "-",
        ocr_chars=len(ocr_text or ""),
        archived_at=_dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        title=Path(source_name).stem,
    )
    lines = [head.rstrip(), ""]
    for k, v in (extra or {}).items():
        if v:
            lines.append(f"> {k}：{v}")
    if extra:
        lines.append("")
    lines.append(OCR_SECTION)
    lines.append("")
    lines.append(ocr_text.strip() if ocr_text and ocr_text.strip() else "（未识别出文字）")
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


OCR_SECTION = "## OCR 文本"


def read_bundle_text(path: Path) -> str:
    """从打包的 text.md 里取回**纯 OCR 原文**（去掉 front matter 与标题）。

    重抽待办时要拿回"当初喂给模型的那段文字"，否则会把元数据也当成材料。
    """
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError:
        return ""
    marker = f"\n{OCR_SECTION}\n"
    idx = raw.find(marker)
    if idx < 0:
        return raw
    body = raw[idx + len(marker):]
    # 去掉写入时补的那个空行
    return body.strip("\n")


# ---------------------------------------------------------------- todos/


def day_dir_name(iso_day: str) -> str:
    """2026-10-14 → 2026.10.14（用户要的「年.月.日」）"""
    return iso_day.replace("-", ".")


def parse_day_dir(name: str) -> str | None:
    """2026.10.14 → 2026-10-14；不是日期目录就返回 None。"""
    if not DAY_DIR_RE.match(name):
        return None
    iso = name.replace(".", "-")
    try:
        _dt.date.fromisoformat(iso)
    except ValueError:
        return None
    return iso


# ---------------------------------------------------------------- 事项文件


ITEM_DIR = "items"
STALE_DIR = "_已撤下"
NOTE_MARKER = "## 备注"
NOTE_TEMPLATE = (
    "## 备注\n"
    "\n"
    "<!-- 这一节之后的内容在重建时原样保留；上面的是自动生成的。 -->\n"
)


# 文件名里不能出现这些：markdown 链接语法会被半角括号截断，
# 反引号/方括号/井号也会破坏各种渲染器。
_SLUG_BAD = re.compile(r"[()\[\]{}<>#?%`|*\"']")


def item_slug(task: str, max_len: int = 40) -> str:
    slug = safe_name(task, max_len + 10, fallback="事项")
    slug = _SLUG_BAD.sub("-", slug)
    slug = re.sub(r"-{2,}", "-", slug).strip("-")
    return slug[:max_len].rstrip("-") or "事项"


def item_filename(start: str, end: str, task: str) -> str:
    """`20261031-参加秋季联赛.md`；跨日则 `20261015~20261018-…md`。"""
    s = (start or "").replace("-", "") or _dt.date.today().strftime("%Y%m%d")
    if end and end != start:
        s += "~" + end.replace("-", "")
    return f"{s}-{item_slug(task)}.md"


def _user_tail(text: str) -> str | None:
    """取出用户自己写的那一段（从「## 备注」到文件末尾），重建时原样保留。"""
    idx = text.find(NOTE_MARKER)
    return text[idx:] if idx >= 0 else None


def _item_markdown(*, task: str, start: str, end: str, votes: int, passes: int,
                   source_name: str, record_path: str, record_rel: str,
                   evidence: str, variants: list[str] | None = None,
                   evidence_ok: bool = True, date_ok: bool = True,
                   redated: str = "", state: str = "active",
                   closed_by: str = "", reopened_by: str = "") -> str:
    span = f"{start}（当天）" if start == end else f"{start} → {end}"
    conf = f"{votes}/{passes} 路" if passes else "-"
    state = state or "active"
    lines = [
        "---",
        f"task: {task}",
        f"start_date: {start}",
        f"end_date: {end}",
        *([f"state: {state}"] if state != "active" else []),
        *([f"closed_by: {closed_by}"] if closed_by else []),
        *([f"reopened_by: {reopened_by}"] if reopened_by else []),
        f"votes: {votes}",
        f"passes: {passes}",
        f"evidence_grounded: {str(bool(evidence_ok)).lower()}",
        f"date_literal: {str(bool(date_ok)).lower()}",
        *([f"redated: {redated}"] if redated else []),
        f"source_name: {source_name}",
        f"record_path: {record_path}",
        "---",
        "",
        f"# {task}",
        "",
    ]
    if state == "done":
        lines.append("- ✅ **已完成**" + (f"（{closed_by} 的通知）" if closed_by else ""))
    elif state == "cancelled":
        lines.append("- ❌ **已取消**" + (f"（{closed_by} 的通知）" if closed_by else ""))
    if reopened_by:
        lines.append(f"- 🔄 **已恢复**（{reopened_by} 的通知：这件又重新办了，重新列为待办）")
    lines += [
        f"- **日期**：{span}",
        f"- **来源**：`{source_name}`" + (f" → `{record_rel}`" if record_rel else ""),
        f"- **抽取一致度**：{conf}" + ("（多路完全一致）" if passes > 1 and votes == passes else ""),
    ]
    if variants and len(variants) > 1:
        lines.append(f"- ⚠️ **多路说法不一致**，已取票数多的那条；其它说法："
                     + " ／ ".join(f"`{v}`" for v in variants if v != task))
    if not evidence_ok:
        lines.append("- ⚠️ **依据与材料没完全对上**（可能是 OCR 错字/折行，也可能是模型改写——"
                     "扫一眼确认）")
    if not date_ok:
        lines.append("- ⚠️ **这个日期在材料里没有字面出现**（可能是『下周三』这类相对说法换算来的；"
                     "若材料也没有相对说法，则日期可疑）")
    if redated:
        lines.append(f"- 🔧 **日期已由依据换算修正**：{redated}")
    if evidence:
        lines.append(f"- **依据**：{' '.join(evidence.split())[:220]}")
    lines.append("")
    return "\n".join(lines) + "\n"


def _day_index_markdown(iso_day: str, items: list, name_of: dict) -> str:
    """时间文件夹里的那个文件：**只记地址**，不重复事项内容。"""
    lines = [
        f"# {day_dir_name(iso_day)} 的事项（{len(items)} 条）",
        "",
        "> 这里只记地址；事项内容在 `../"
        + ITEM_DIR + "/` 下各自一个文件。",
        "> 由 `inbox todos --rebuild` 生成，手工改动会在下次重建时被覆盖。",
        "",
    ]
    if not items:
        lines += ["（这天没有事项）", ""]
        return "\n".join(lines)
    for r in items:
        # 链接目标用 <...> 包起来：文件名里万一还有括号之类的字符，也不会把链接截断
        st = (r["state"] if "state" in r.keys() else "active") or "active"
        mark = "✅ " if st == "done" else ("❌ " if st == "cancelled" else "")
        lines.append(f"- {mark}[{r['task']}](<../{ITEM_DIR}/{name_of[r['id']]}>)")
    lines.append("")
    return "\n".join(lines)


def _row_json(r, name: str | None = None) -> dict:
    return {
        "id": r["id"],
        "task": r["task"],
        "start_date": r["start_date"],
        "end_date": r["end_date"],
        "source_name": r["source_name"],
        "record_path": r["record_path"],
        "evidence": r["evidence"],
        "votes": r["votes"] if "votes" in r.keys() else 1,
        "passes": r["passes"] if "passes" in r.keys() else 1,
        "variants": json.loads(r["variants"]) if "variants" in r.keys() and r["variants"] else [],
        "evidence_grounded": bool(r["evidence_ok"]) if "evidence_ok" in r.keys() else True,
        "date_literal": bool(r["date_ok"]) if "date_ok" in r.keys() else True,
        "redated": (r["redated"] if "redated" in r.keys() and r["redated"] else ""),
        "state": (r["state"] if "state" in r.keys() and r["state"] else "active"),
        "closed_by": (r["closed_by"] if "closed_by" in r.keys() and r["closed_by"] else ""),
        "closed_at": (r["closed_at"] if "closed_at" in r.keys() and r["closed_at"] else ""),
        "reopened_by": (r["reopened_by"] if "reopened_by" in r.keys() and r["reopened_by"] else ""),
        "reopened_at": (r["reopened_at"] if "reopened_at" in r.keys() and r["reopened_at"] else ""),
        "item_file": f"{ITEM_DIR}/{name}" if name else None,
    }


def rebuild_todos(cfg, store: Store) -> dict:
    """从状态库重建 todo 目录（幂等）：

        todos/items/<每个事项一个文件>.md    ← 内容在这
        todos/2026.10.31/items.md            ← 只有地址（markdown 链接）
        todos/2026.10.31/items.txt           ← 只有地址（纯路径，一行一个）
        todos/index.jsonl                    ← 全部事项的索引
    """
    root = cfg.paths.todos
    root.mkdir(parents=True, exist_ok=True)

    # 1) 清掉旧日目录（只删长得像日期的，别动用户自己放进去的东西）
    removed = 0
    for child in list(root.iterdir()):
        if child.is_dir() and parse_day_dir(child.name):
            shutil.rmtree(child, ignore_errors=True)
            removed += 1
        elif child.is_file() and child.name in ("index.jsonl", "todos.jsonl"):
            child.unlink()

    rows = store.all_todos()
    items_dir = root / ITEM_DIR
    items_dir.mkdir(parents=True, exist_ok=True)

    # 2) 事项文件：一件一个，文件名带日期与标题
    used: set[str] = set()
    name_of: dict[int, str] = {}
    for r in rows:
        base = item_filename(r["start_date"], r["end_date"], r["task"])
        name = base
        n = 2
        while name in used:                      # 同名（同一天两件同名事）加 -2
            stem, suffix = base[:-3], base[-3:]
            name = f"{stem}-{n}{suffix}"
            n += 1
        used.add(name)
        name_of[r["id"]] = name
        path = items_dir / name
        # 保住用户写在「## 备注」之后的内容（否则重建会把手记的东西抹掉）
        tail = _user_tail(path.read_text(encoding="utf-8")) if path.exists() else None
        record_rel = ""
        try:
            record_rel = str(Path(r["record_path"]).relative_to(cfg.paths.root))
        except (ValueError, TypeError):
            record_rel = r["record_path"] or ""
        keys = r.keys()
        try:
            variants = json.loads(r["variants"]) if "variants" in keys and r["variants"] else []
        except (json.JSONDecodeError, TypeError):
            variants = []
        head = _item_markdown(
            task=r["task"], start=r["start_date"], end=r["end_date"],
            votes=r["votes"] if "votes" in keys else 1,
            passes=r["passes"] if "passes" in keys else 1,
            source_name=r["source_name"] or "", record_path=r["record_path"] or "",
            record_rel=record_rel, evidence=r["evidence"] or "",
            variants=variants,
            evidence_ok=bool(r["evidence_ok"]) if "evidence_ok" in keys else True,
            date_ok=bool(r["date_ok"]) if "date_ok" in keys else True,
            redated=("redated" in keys and r["redated"]) or "",
            state=("state" in keys and r["state"]) or "active",
            closed_by=("closed_by" in keys and r["closed_by"]) or "",
            reopened_by=("reopened_by" in keys and r["reopened_by"]) or "")
        path.write_text(head + (tail or NOTE_TEMPLATE), encoding="utf-8")

    # 3) 已撤下的事项：有用户备注的挪到 _已撤下/，纯生成物直接删
    stale_dir = items_dir / STALE_DIR
    for f in list(items_dir.glob("*.md")):
        if f.name in used:
            continue
        tail = _user_tail(f.read_text(encoding="utf-8"))
        if tail and tail.strip() != NOTE_TEMPLATE.strip():
            stale_dir.mkdir(parents=True, exist_ok=True)
            transfer(f, unique_path(stale_dir / f.name), "move")
        else:
            f.unlink()

    # 4) 时间文件夹：只放地址
    days: dict[str, list] = {}
    for r in rows:
        try:
            d0 = _dt.date.fromisoformat(r["start_date"])
            d1 = _dt.date.fromisoformat(r["end_date"])
        except (TypeError, ValueError):
            continue
        cur = d0
        while cur <= d1:
            days.setdefault(cur.isoformat(), []).append(r)
            cur += _dt.timedelta(days=1)

    for iso_day, items in sorted(days.items()):
        d = root / day_dir_name(iso_day)
        d.mkdir(parents=True, exist_ok=True)
        (d / "items.md").write_text(_day_index_markdown(iso_day, items, name_of),
                                    encoding="utf-8")
        (d / "items.txt").write_text(
            "".join(f"../{ITEM_DIR}/{name_of[r['id']]}\n" for r in items),
            encoding="utf-8")

    with (root / "index.jsonl").open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(_row_json(r, name_of.get(r["id"])),
                                ensure_ascii=False) + "\n")

    return {"todos": len(rows), "days": len(days), "removed_dirs": removed,
            "items": len(used), "stale_kept": len(list(stale_dir.glob("*.md")))
            if stale_dir.is_dir() else 0}
