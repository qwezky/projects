"""主流程：扫 raw/ → 判稳定 → 文本直入 records/、图片 OCR 后打包 → 抽待办 → 重建 todos/。"""
from __future__ import annotations

import datetime as _dt
import json
import fnmatch
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import library, preheat, services, todos as todos_mod, vision
from .config import Config
from .store import (DEFERRED, DUPLICATE, FAILED, FILED, FOREIGN, Store)
from .util import (READABLE_TEXT_EXT, detect_kind, human_size, now_str,
                   read_image_info, read_text_best_effort, sha256_of)


@dataclass
class Options:
    dry_run: bool = False
    limit: int = 0
    only: list[str] = field(default_factory=list)
    copy: bool = False
    no_ocr: bool = False
    no_llm: bool = False
    # 大图 OCR 前不做热启动（默认做；见 archiver/preheat.py）
    preheat: bool = True
    # 模型服务拉不起来也照样归档（只是抽不出待办），而不是留在 raw 等下次
    force: bool = False
    allow_duplicate: bool = False
    # 抽取待办只问一次模型（快；准确率优先时别开）
    fast: bool = False
    verbose: bool = True
    # 换一个来源目录（默认 = 配置的 raw/）。用来一次性整理旧的 phone-upload 之类。
    source: Path | None = None


@dataclass
class Scan:
    ready: list[Path] = field(default_factory=list)
    waiting: list[tuple[Path, int]] = field(default_factory=list)
    ignored: list[tuple[Path, str]] = field(default_factory=list)
    foreign: list[Path] = field(default_factory=list)


def scan(cfg: Config, source: Path | None = None) -> Scan:
    out = Scan()
    root = Path(source) if source else cfg.paths.raw
    if not root.is_dir():
        return out
    now = time.time()
    walker = root.rglob("*") if cfg.filters.recursive else root.glob("*")
    for p in sorted(walker):
        if not p.is_file() or p.is_symlink():
            continue
        rel = p.relative_to(root)
        if any(part.startswith(".") for part in rel.parts):
            continue
        try:
            st = p.stat()
        except OSError:
            continue
        hit = _ignored(rel, p.name, cfg)
        if hit:
            out.ignored.append((p, hit))
            continue
        if st.st_size < cfg.filters.min_size:
            out.ignored.append((p, f"小于 {cfg.filters.min_size} 字节"))
            continue
        if now - st.st_mtime < cfg.filters.min_stable_seconds:
            out.waiting.append((p, int(cfg.filters.min_stable_seconds - (now - st.st_mtime))))
            continue
        out.ready.append(p)
    out.ready.sort(key=lambda x: x.stat().st_mtime)
    out.foreign = [p for p in out.ready if detect_kind(p.name) not in ("text", "image")]
    return out


def _ignored(rel: Path, name: str, cfg: Config) -> str:
    for pat in cfg.filters.ignore_globs:
        if fnmatch.fnmatch(name, pat) or fnmatch.fnmatch(str(rel), pat):
            return f"命中忽略规则 {pat}"
    return ""


def _doc_date(path: Path, info) -> str:
    if info is not None and getattr(info, "exif_datetime", None):
        return info.exif_datetime.date().isoformat()
    from .util import date_from_filename
    dt = date_from_filename(path.name)
    if dt:
        return dt.date().isoformat()
    return _dt.date.fromtimestamp(path.stat().st_mtime).isoformat()


def describe(path: Path):
    """元数据快照：类型 / 日期 / 图片信息 / 给模型看的额外字段。"""
    kind = detect_kind(path.name)
    info = read_image_info(path) if kind == "image" else None
    doc_date = _doc_date(path, info)
    extra: dict[str, str] = {}
    if info is not None:
        if info.ok:
            extra["图片尺寸"] = f"{info.width}x{info.height} {info.fmt}".strip()
        if info.camera:
            extra["拍摄设备"] = info.camera
        if info.exif_datetime:
            extra["拍摄时间"] = info.exif_datetime.strftime("%Y-%m-%d %H:%M:%S")
    return kind, doc_date, info, extra


# ---------------------------------------------------------------- 待办落库


def _apply_closes(store: Store, res, *, item_id: int, source_name: str,
                  doc_date: str, verbose: bool = True) -> list[str]:
    """把材料里的状态通知（「已完成 / 已取消」）落到**已有的**待办上。

    只改状态、不删东西：事项文件会带着「✅已完成 / ❌已取消」的标注留在 `todos/` 里。
    对不上的不动，并说明一句 —— 宁可留一条已经取消的事在列表里让你看见，
    也不要凭空关掉一条真待办。
    """
    if not res.closes:
        return []
    mine = {r["id"] for r in store.conn.execute(
        "SELECT id FROM todos WHERE item_id = ?", (item_id,)).fetchall()}
    out: list[str] = []
    handled: set[int] = set()
    for sig in res.closes:
        if sig.state == "reopen":
            label = "已恢复"
            cands = [r for r in store.todos_in_state("cancelled") if r["id"] not in mine]
        else:
            label = "已完成" if sig.state == "done" else "已取消"
            cands = [r for r in store.active_todos() if r["id"] not in mine]
        hit = todos_mod.match_close(sig, cands)
        if hit is None:
            out.append(f"⚠️ 通知说「{sig.task}」{label}，但当前没有对应的待办，没有动任何东西")
            continue
        row, score = hit
        if row["id"] in handled:
            continue        # 同一份通知说了两遍（模型两栏 + 代码扫描）→ 静默去重
        by = f"{source_name}（材料日期 {doc_date}）"
        if sig.state == "reopen":
            store.reopen_todo(row["id"], by=by, ts=now_str())
        else:
            store.close_todo(row["id"], state=sig.state, closed_by=by, ts=now_str())
        handled.add(row["id"])
        out.append(f"{label}：{row['task']}（依据「{sig.task}」，相似度 {score:.2f}）")
    if verbose:
        for line in out:
            print(f"    ✔ {line}" if not line.startswith("⚠️") else f"    {line}")
    return out


def _record_todos(cfg: Config, store: Store, *, item_id: int, source_name: str,
                  record_path: str, body: str, doc_date: str,
                  opts: Options) -> tuple[int, str]:
    """抽待办并入库。返回 (条数, 出错信息)。"""
    if opts.no_llm or not (body or "").strip():
        return 0, ""
    res = todos_mod.extract(source_name=source_name, doc_date=doc_date,
                            body_text=body, cfg=cfg, fast=opts.fast)
    for e in res.entries:
        store.add_todo(item_id=item_id, source_name=source_name, record_path=record_path,
                       task=e.task, start_date=e.start, end_date=e.end,
                       evidence=e.evidence, votes=e.votes, passes=res.passes,
                       variants=json.dumps(e.variants, ensure_ascii=False),
                       evidence_ok=1 if e.evidence_grounded else 0,
                       date_ok=1 if e.date_literal else 0,
                       redated=e.redated,
                       created_at=now_str())
    _apply_closes(store, res, item_id=item_id, source_name=source_name,
                  doc_date=doc_date, verbose=opts.verbose)
    if opts.verbose:
        detail = "；".join(res.notes)
        print(f"    待办 {len(res.entries)} 条"
              f"{f'（{res.dropped_no_date} 条没写日期、按规则不入 todos）' if res.dropped_no_date else ''}"
              f"{f'  [{detail}]' if detail else ''}")
        for e in res.entries:
            span = e.start if e.start == e.end else f"{e.start}→{e.end}"
            print(f"      · {span}  {e.task}  ({e.votes}/{res.passes} 路)")
    return len(res.entries), res.error


def item_body(cfg: Config, row) -> str:
    """取回某条已归档内容当初喂给模型的正文（重抽待办时用）。"""
    if row["record_kind"] == "bundle":
        tp = row["text_path"] or str(Path(row["record_path"]) / "text.md")
        return library.read_bundle_text(Path(tp)) if Path(tp).is_file() else ""
    p = Path(row["record_path"] or "")
    if p.is_file() and p.suffix.lower() in READABLE_TEXT_EXT:
        return read_text_best_effort(p)
    return ""


def reextract(cfg: Config, store: Store, rows: list, *, dry_run: bool = False,
              verbose: bool = True, fast: bool = False) -> dict:
    """对已归档的条目**重跑待办抽取**（模型或提示词改好后用来修正历史）。

    不动 records/ 里的文件，只替换状态库里的 todos，然后重建 todos/ 目录。
    """
    out = {"items": 0, "before": 0, "after": 0, "skipped": 0, "results": []}
    # 状态通知（「已完成 / 已取消」）要等**所有材料都重抽完**再统一落实：
    # 否则先被关掉的条目，会被后面同一轮里那条材料的重抽覆盖回 active
    pending: list[tuple] = []
    for row in rows:
        body = item_body(cfg, row)
        if not body.strip():
            out["skipped"] += 1
            if verbose:
                print(f"  · {row['src_name']} —— 取不到正文，跳过")
            continue
        before = len(store.todos_of_item(row["id"]))
        res = todos_mod.extract(source_name=row["src_name"],
                                doc_date=(row["doc_date"] or "")[:10],
                                body_text=body, cfg=cfg, fast=fast)
        out["items"] += 1
        out["before"] += before
        out["after"] += len(res.entries)
        out["results"].append({"id": row["id"], "src_name": row["src_name"],
                               "entries": res.entries, "error": res.error,
                               "dropped_no_date": res.dropped_no_date})
        if verbose:
            print(f"  · {row['src_name']}：{before} 条 → {len(res.entries)} 条"
                  f"{'  [' + '；'.join(res.notes) + ']' if res.notes else ''}")
            for e in res.entries:
                span = e.start if e.start == e.end else f"{e.start} → {e.end}"
                print(f"      {span}  {e.task}  ({e.votes}/{res.passes} 路)")
        if dry_run:
            continue
        store.delete_todos_of_item(row["id"])
        for e in res.entries:
            store.add_todo(item_id=row["id"], source_name=row["src_name"],
                           record_path=row["record_path"], task=e.task,
                           start_date=e.start, end_date=e.end, evidence=e.evidence,
                           votes=e.votes, passes=res.passes,
                           variants=json.dumps(e.variants, ensure_ascii=False),
                           evidence_ok=1 if e.evidence_grounded else 0,
                           date_ok=1 if e.date_literal else 0,
                           redated=e.redated,
                           created_at=now_str())
        _apply_closes_pending = (row["id"], row["src_name"],
                                 (row["doc_date"] or "")[:10], res)
        pending.append(_apply_closes_pending)
        store.update_item(row["id"], todo_count=len(res.entries),
                          error=res.error or None)
    # 所有材料重建完之后，再统一落实状态通知
    for item_id, src_name, doc_date, res in pending:
        _apply_closes(store, res, item_id=item_id, source_name=src_name,
                      doc_date=doc_date, verbose=verbose)
    if not dry_run and out["items"]:
        library.rebuild_todos(cfg, store)
    return out


# ---------------------------------------------------------------- 单个文件


def _defer(store: Store, path: Path, st, sha: str, kind: str, doc_date: str,
           reason: str) -> dict:
    store.forget_source(str(path))
    store.add_item(src_name=path.name, src_path=str(path), size=st.st_size,
                   mtime=st.st_mtime, sha256=sha, kind=kind, doc_date=doc_date,
                   status=DEFERRED, error=reason, created_at=now_str(),
                   updated_at=now_str())
    return {"status": DEFERRED, "path": path, "reason": reason}


def _duplicate(cfg: Config, store: Store, path: Path, st, sha: str, kind: str,
               doc_date: str, opts: Options) -> dict:
    dup_dir = cfg.paths.raw / cfg.behavior.duplicate_dir
    dup_dir.mkdir(parents=True, exist_ok=True)
    target = library.unique_path(dup_dir / path.name)
    if not opts.dry_run:
        library.transfer(path, target, "copy" if opts.copy else "move")
        store.forget_source(str(path))
        store.add_item(src_name=path.name, src_path=str(path), size=st.st_size,
                       mtime=st.st_mtime, sha256=sha, kind=kind,
                       doc_date=doc_date, status=DUPLICATE,
                       record_path=str(target), created_at=now_str(), updated_at=now_str())
    return {"status": DUPLICATE, "path": path, "target": target}


def _foreign(cfg: Config, store: Store, path: Path, st, sha: str, doc_date: str,
             opts: Options) -> dict:
    action = cfg.behavior.foreign_action
    if action == "move":
        target = library.text_record_path(cfg.paths.records, path.name,
                                          cfg.behavior.name_max)
        if not opts.dry_run:
            library.transfer(path, target, "copy" if opts.copy else "move")
            store.forget_source(str(path))
            store.add_item(src_name=path.name, src_path=str(path), size=st.st_size,
                           mtime=st.st_mtime, sha256=sha, kind="foreign",
                           doc_date=doc_date, status=FILED, record_kind="file",
                           record_path=str(target), created_at=now_str(),
                           updated_at=now_str())
        return {"status": FILED, "path": path, "record": target, "foreign": True}
    if action == "ignore":
        return {"status": "ignored", "path": path}
    # error：原地不动，只记一条
    reason = f"存档区只收文本与图片，这个类型不收：{path.name}"
    if not opts.dry_run:
        store.forget_source(str(path))
        store.add_item(src_name=path.name, src_path=str(path), size=st.st_size,
                       mtime=st.st_mtime, sha256=sha, kind="foreign",
                       doc_date=doc_date, status=FOREIGN, error=reason,
                       created_at=now_str(), updated_at=now_str())
    return {"status": FOREIGN, "path": path, "reason": reason}


def process_one(path: Path, cfg: Config, store: Store, opts: Options,
                health: dict[str, bool]) -> dict:
    st = path.stat()
    size, mtime = st.st_size, st.st_mtime
    sha = sha256_of(path)
    kind, doc_date, info, extra = describe(path)

    dup = store.by_sha(sha)
    if dup is not None and not opts.allow_duplicate:
        return _duplicate(cfg, store, path, st, sha, kind, doc_date, opts)

    if kind not in ("text", "image"):
        return _foreign(cfg, store, path, st, sha, doc_date, opts)

    need_ocr = False
    if kind == "image" and not opts.no_ocr:
        if (cfg.models.ocr_mode or "always").lower() == "auto":
            need_ocr, _why = vision.should_ocr(path.name, info, cfg)
        else:
            need_ocr = True
    need_llm = not opts.no_llm
    defer = (cfg.behavior.on_model_error == "defer") and not opts.force

    if need_ocr and not health.get("ocr"):
        if defer:
            return _defer(store, path, st, sha, kind, doc_date,
                          "OCR 服务（PaddleOCR-VL）未就绪；修好后重跑即可")
        need_ocr = False
    if need_llm and not health.get("llm"):
        if defer:
            return _defer(store, path, st, sha, kind, doc_date,
                          "Qwen3-4B 未就绪（抽不了待办）；修好后重跑即可")
        need_llm = False

    errors: list[str] = []
    title = Path(path.name).stem

    if kind == "text":
        body = read_text_best_effort(path)
        target = library.text_record_path(cfg.paths.records, path.name,
                                          cfg.behavior.name_max)
        if opts.dry_run:
            return {"status": "dry-run", "path": path, "record": target, "kind": kind}
        library.transfer(path, target, "copy" if opts.copy else "move")
        item_id = store.add_item(
            src_name=path.name, src_path=str(path), size=size, mtime=mtime, sha256=sha,
            kind=kind, doc_date=doc_date, status=FILED, record_path=str(target),
            record_kind="file", created_at=now_str(), updated_at=now_str())
        n, err = _record_todos(cfg, store, item_id=item_id, source_name=path.name,
                               record_path=str(target), body=body, doc_date=doc_date,
                               opts=opts)
        if err:
            errors.append(err)
        store.update_item(item_id, todo_count=n, error="; ".join(errors) or None)
        return {"status": FILED, "path": path, "record": target, "kind": kind,
                "todos": n, "id": item_id, "error": "; ".join(errors)}

    # ---- 图片：OCR → 打包 ----
    ocr_text, ocr_engine = "", ""
    if need_ocr:
        res = vision.run_ocr(path, cfg)
        ocr_engine = res.engine
        if res.ok:
            ocr_text = res.text
        else:
            errors.append(res.error or "OCR 无输出")
        if opts.verbose:
            print(f"    OCR {res.elapsed:.0f}s · {len(ocr_text)} 字")
    elif not opts.no_ocr:
        errors.append("OCR 服务不可用，text.md 里没有文字")

    if opts.dry_run:
        bundle = library.image_bundle_dir(cfg.paths.records, path.name,
                                          cfg.behavior.bundle_name_max)
        return {"status": "dry-run", "path": path, "record": bundle, "kind": kind}

    bundle = library.image_bundle_dir(cfg.paths.records, path.name,
                                      cfg.behavior.bundle_name_max)
    bundle.mkdir(parents=True, exist_ok=True)
    library.transfer(path, bundle / path.name, "copy" if opts.copy else "move")
    text_path = library.write_bundle_text(
        bundle, source_name=path.name, size=size, sha256=sha, doc_date=doc_date,
        ocr_engine=ocr_engine, ocr_text=ocr_text, extra=extra)

    item_id = store.add_item(
        src_name=path.name, src_path=str(path), size=size, mtime=mtime, sha256=sha,
        kind=kind, doc_date=doc_date, status=FILED, record_path=str(bundle),
        record_kind="bundle", text_path=str(text_path), ocr_engine=ocr_engine,
        ocr_chars=len(ocr_text), created_at=now_str(), updated_at=now_str())
    n, err = _record_todos(cfg, store, item_id=item_id, source_name=path.name,
                           record_path=str(bundle), body=ocr_text, doc_date=doc_date,
                           opts=opts)
    if err:
        errors.append(err)
    store.update_item(item_id, todo_count=n, error="; ".join(errors) or None)
    return {"status": FILED, "path": path, "record": bundle, "kind": kind,
            "todos": n, "id": item_id, "ocr_chars": len(ocr_text),
            "error": "; ".join(errors)}


# ---------------------------------------------------------------- 整轮


def run(cfg: Config, store: Store, opts: Options) -> dict:
    root = Path(opts.source) if opts.source else cfg.paths.raw
    sc = scan(cfg, root)
    only = [o.lower() for o in opts.only]
    ready = [p for p in sc.ready
             if not only or any(o in str(p).lower() or o in p.name.lower() for o in only)]
    if opts.limit:
        ready = ready[:opts.limit]

    summary = {"ready": len(ready), "waiting": len(sc.waiting), "ignored": len(sc.ignored),
               "filed": 0, "bundled": 0, "duplicate": 0, "foreign": 0,
               "deferred": 0, "failed": 0, "todos": 0, "dry_run": opts.dry_run,
               "source": str(root), "results": []}

    if opts.verbose:
        print(f"收件箱 {root}")
        print(f"  待处理 {len(ready)} · 传着/太新 {len(sc.waiting)} · 忽略 {len(sc.ignored)}")
        for p, why in sc.ignored[:10]:
            print(f"    [忽略] {p.name} — {why}")
        for p in sc.foreign[:10]:
            print(f"    [非文本/图片] {p.name} — 按 foreign_action="
                  f"{cfg.behavior.foreign_action} 处理")

    if not ready:
        return summary

    health = {"llm": services.llm_check(cfg),
              "ocr": services.health(cfg.models.ocr_host, cfg.models.ocr_port)}

    if opts.dry_run:
        for p in ready:
            kind = detect_kind(p.name)
            if kind == "text":
                t = library.text_record_path(cfg.paths.records, p.name, cfg.behavior.name_max)
                print(f"  [计划] {p.name} ({human_size(p.stat().st_size)}) "
                      f"→ 文本直入 records/{t.name}")
            elif kind == "image":
                b = library.image_bundle_dir(cfg.paths.records, p.name,
                                             cfg.behavior.bundle_name_max)
                print(f"  [计划] {p.name} ({human_size(p.stat().st_size)}) "
                      f"→ OCR 后打包 records/{b.name}/{{原图,text.md}}")
            else:
                print(f"  [计划] {p.name} ({human_size(p.stat().st_size)}) "
                      f"→ 非文本/图片，foreign_action={cfg.behavior.foreign_action}")
        return summary

    want_ocr = (not opts.no_ocr) and any(detect_kind(p.name) == "image" for p in ready)
    services.ensure(cfg, need_llm=not opts.no_llm, need_ocr=want_ocr,
                    verbose=(print if opts.verbose else (lambda *_: None)))
    health = {"llm": services.llm_check(cfg),
              "ocr": services.health(cfg.models.ocr_host, cfg.models.ocr_port)}
    if opts.verbose:
        print(f"  模型：{services.llm_label(cfg)}={'就绪' if health['llm'] else '不可用'} · "
              f"PaddleOCR-VL={'就绪' if health['ocr'] else '不可用'}")

    # 热启动：在第一张大图 OCR 之前，先用 2 线程把温度抬到目标值，
    # 让 EC 按它自己的曲线先把风扇带到工作点（详见 archiver/preheat.py）。
    # 只有「这轮真要 OCR 且服务就绪」才做，否则白烧 CPU。
    if opts.preheat and want_ocr and health.get("ocr"):
        preheat.warm_for(cfg, ready,
                         verbose=(print if opts.verbose else (lambda *_: None)))

    for i, p in enumerate(ready, 1):
        if opts.verbose:
            print(f"  [{i}/{len(ready)}] {p.name} ({human_size(p.stat().st_size)})")
        try:
            res = process_one(p, cfg, store, opts, health)
        except Exception as exc:  # noqa: BLE001
            res = {"status": FAILED, "path": p, "reason": repr(exc)}
        status = res.get("status")
        if status == FAILED:
            summary["failed"] += 1
            try:
                pst = p.stat()
                size, mtime = pst.st_size, pst.st_mtime
            except OSError:
                size, mtime = 0, 0.0
            store.forget_source(str(p))
            store.add_item(src_name=p.name, src_path=str(p), size=size, mtime=mtime,
                           status=FAILED, error=str(res.get("reason")),
                           created_at=now_str(), updated_at=now_str())
            print(f"    ✗ 失败：{res.get('reason')}")
        elif status == DEFERRED:
            summary["deferred"] += 1
            print(f"    ⏸ 暂缓：{res.get('reason')}")
        elif status == FOREIGN:
            summary["foreign"] += 1
            print(f"    ⚠ {res.get('reason')}")
        elif status == DUPLICATE:
            summary["duplicate"] += 1
            print(f"    ≡ 重复内容，另存到 raw/{cfg.behavior.duplicate_dir}/")
        elif status == "ignored":
            summary["foreign"] += 1
            print("    · 按 foreign_action=ignore 原样保留")
        else:
            summary["filed"] += 1
            if res.get("kind") == "image":
                summary["bundled"] += 1
            summary["todos"] += res.get("todos", 0) or 0
            name = Path(res["record"]).name
            print(f"    ✓ records/{name}"
                  + ("/" if res.get("kind") == "image" else "")
                  + f"  ({res.get('todos', 0)} 条待办)")
            if res.get("error"):
                print(f"      ⚠ {res['error']}")
        summary["results"].append(res)

    if summary["filed"] or summary["duplicate"]:
        info = library.rebuild_todos(cfg, store)
        if opts.verbose:
            print(f"  待办索引已重建：{info['days']} 个日期目录 · 共 {info['todos']} 条")
    return summary
