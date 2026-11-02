"""命令行入口。"""
from __future__ import annotations

import argparse
import datetime as _dt
import fcntl
import json
import logging
import os
import subprocess
import sys
import time
from pathlib import Path

from . import __version__, library, pipeline, services, todos as todos_mod, vision
from .config import Config, load
from .pipeline import Options, describe, reextract, run, scan
from .store import Store
from .util import human_size, read_text_best_effort, READABLE_TEXT_EXT

log = logging.getLogger("inbox")


def _setup_logging(cfg: Config, quiet: bool) -> None:
    log.setLevel(logging.INFO)
    log.handlers.clear()
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%Y-%m-%d %H:%M:%S")
    sh = logging.StreamHandler(sys.stderr)
    sh.setFormatter(fmt)
    sh.setLevel(logging.WARNING if quiet else logging.INFO)
    log.addHandler(sh)
    fh = logging.FileHandler(cfg.log_path, encoding="utf-8")
    fh.setFormatter(fmt)
    log.addHandler(fh)


def _store(cfg: Config) -> Store:
    return Store(cfg.db_path)


def _acquire_run_lock(cfg: Config):
    """同一时间只允许一轮归档。返回文件对象（持有锁）或 None（已有在跑）。"""
    lock_path = cfg.paths.state_dir / "run.lock"
    fh = lock_path.open("w")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        fh.close()
        return None
    fh.write(str(os.getpid()))
    fh.flush()
    return fh


# ---------------------------------------------------------------- 子命令


def cmd_run(cfg: Config, args) -> int:
    lock = None
    if args.lock:
        lock = _acquire_run_lock(cfg)
        if lock is None:
            print("已有一轮归档在跑，本次跳过。", file=sys.stderr)
            return 0
        delay = max(0, args.delay if args.delay is not None else cfg.behavior.trigger_delay_seconds)
        if delay:
            log.info("触发后先等 %ds，避开手机那头还没写完的文件", delay)
            time.sleep(delay)

    opts = Options(dry_run=args.dry_run, limit=args.limit, only=args.only or [],
                   copy=args.copy, no_ocr=args.no_ocr, no_llm=args.no_llm,
                   preheat=not args.no_preheat,
                   force=args.force, allow_duplicate=args.allow_duplicate,
                   fast=args.fast, verbose=not args.quiet,
                   source=Path(args.source).expanduser() if args.source else None)
    store = _store(cfg)
    try:
        summary = run(cfg, store, opts)
    finally:
        store.close()
        if lock is not None:
            lock.close()
    print(f"\n本轮：入档案 {summary['filed']}（其中图片打包 {summary['bundled']}）· "
          f"抽出待办 {summary['todos']} 条 · 重复 {summary['duplicate']} · "
          f"不收的类型 {summary['foreign']} · 暂缓 {summary['deferred']} · "
          f"失败 {summary['failed']} · 未就绪 {summary['waiting']} · 忽略 {summary['ignored']}")
    if summary["deferred"]:
        print("暂缓的文件仍在 raw/；模型服务修好后重跑 `inbox run` 即可。")
    return 0


def cmd_status(cfg: Config, args) -> int:
    sc = scan(cfg)
    store = _store(cfg)
    try:
        counts = store.item_counts()
        pending = store.pending()
        todo_days = store.todo_days()
        n_todos = len(store.all_todos())
    finally:
        store.close()
    st = services.status(cfg)
    print(f"归档库根 {cfg.paths.root}")
    print(f"  原文件   {cfg.paths.raw}")
    print(f"  档案     {cfg.paths.records}")
    print(f"  事项     {cfg.paths.todos}")
    print(f"状态目录 {cfg.paths.state_dir}    配置 {cfg.source}")
    print()
    print(f"raw/：待处理 {len(sc.ready)} · 传着/太新 {len(sc.waiting)} · 忽略 {len(sc.ignored)}")
    if sc.foreign:
        print(f"      非文本/图片 {len(sc.foreign)} 个（按 foreign_action="
              f"{cfg.behavior.foreign_action} 处理）")
    print("历史： " + (" · ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "（还没跑过）"))
    print(f"待办： {n_todos} 条 · 覆盖 {len(todo_days)} 天"
          + (f"（最早 {todo_days[0]}，最晚 {todo_days[-1]}）" if todo_days else ""))
    print()
    print(f"Qwen3-4B     {'✅ 就绪' if st['llm'].up else '❌ 不可用'}  {st['llm'].detail}")
    print(f"PaddleOCR-VL {'✅ 就绪' if st['ocr'].up else '❌ 不可用'}  {st['ocr'].detail}")
    if sc.ready:
        print("\n下次会处理：")
        for p in sc.ready[:10]:
            print(f"  · {p.name}  {human_size(p.stat().st_size)}")
        if len(sc.ready) > 10:
            print(f"  … 还有 {len(sc.ready) - 10} 个")
    if pending:
        print("\n暂缓/失败待重试：")
        for r in pending[:10]:
            print(f"  · {r['src_name']} — {r['error']}")
    return 0


def cmd_todos(cfg: Config, args) -> int:
    store = _store(cfg)
    try:
        if args.rebuild:
            info = library.rebuild_todos(cfg, store)
            print(f"已重建 {cfg.paths.todos}：{info['days']} 个日期目录 · "
                  f"{info['todos']} 条待办（清掉旧目录 {info['removed_dirs']} 个）")
            if not (args.start or args.end or args.month or args.last):
                return 0

        start, end = args.start, args.end
        if args.month:
            y, m = (int(x) for x in args.month.replace(".", "-").split("-")[:2])
            start = _dt.date(y, m, 1).isoformat()
            end = (_dt.date(y + (m == 12), (m % 12) + 1, 1)
                   - _dt.timedelta(days=1)).isoformat()
        if args.last:
            today = _dt.date.today()
            start = (today - _dt.timedelta(days=args.last - 1)).isoformat()
            end = today.isoformat()

        if start or end:
            lo = start or "0001-01-01"
            hi = end or "9999-12-31"
            rows = store.todos_in_range(lo, hi)
            title = f"{lo} ~ {hi}"
        else:
            rows = store.all_todos()
            title = "全部"

        if getattr(args, "active", False):
            rows = [r for r in rows
                    if ((r["state"] if "state" in r.keys() else "active") or "active") == "active"]

        if args.json:
            for r in rows:
                print(json.dumps(dict(r), ensure_ascii=False))
            return 0

        done_n = sum(1 for r in rows
                     if ((r["state"] if "state" in r.keys() else "active") or "active") == "done")
        cancel_n = sum(1 for r in rows
                       if ((r["state"] if "state" in r.keys() else "active") or "active")
                       == "cancelled")
        settled = (f"，其中已办结 {done_n + cancel_n} 条（✅ {done_n} / ❌ {cancel_n}）"
                   if done_n or cancel_n else "")
        print(f"待办事项（{title}）：共 {len(rows)} 条{settled}")
        print()
        if not rows:
            print("（没有）")
            return 0
        # 按「开始日期」分组，跨日事项标注区间
        cur = None
        for r in rows:
            if r["start_date"] != cur:
                cur = r["start_date"]
                print(f"── {cur} ──")
            span = "" if r["start_date"] == r["end_date"] else f"（{r['start_date']} → {r['end_date']}）"
            tail = "" if not args.show_source else f"   ← {r['source_name']}"
            st = ((r["state"] if "state" in r.keys() else "active") or "active")
            if st == "done":
                mark, note = "✅", "（已完成）"
            elif st == "cancelled":
                mark, note = "❌", "（已取消）"
            else:
                mark, note = "[ ]", ""
            print(f"  {mark} {r['task']}{span}{tail}{note}")
        return 0
    finally:
        store.close()


def cmd_list(cfg: Config, args) -> int:
    store = _store(cfg)
    try:
        rows = store.recent_items(limit=args.number, status=args.status)
    finally:
        store.close()
    if not rows:
        print("（没有记录）")
        return 0
    for r in rows:
        rec = r["record_path"] or "-"
        try:
            rec = str(Path(rec).relative_to(cfg.paths.root))
        except ValueError:
            pass
        print(f"{r['id']:>4}  {r['status']:<10} {r['kind'] or '-':<8} "
              f"{(r['doc_date'] or '')[:10]}  {r['src_name']}")
        if r["record_path"]:
            print(f"        → {rec}{'/' if r['record_kind'] == 'bundle' else ''}"
                  f"   待办 {r['todo_count'] or 0} 条")
        if r["error"]:
            print(f"        ! {r['error'][:120]}")
    return 0


def cmd_undo(cfg: Config, args) -> int:
    store = _store(cfg)
    try:
        row = store.get_item(args.id)
        if row is None:
            print(f"没有 id={args.id} 的记录", file=sys.stderr)
            return 1
        if row["status"] not in ("filed", "duplicate"):
            print(f"id={args.id} 状态是 {row['status']}，没有可退回的归档", file=sys.stderr)
            return 1
        rec = Path(row["record_path"])
        if not rec.exists():
            print(f"归档内容不存在：{rec}", file=sys.stderr)
            return 1
        back = library.unique_path(cfg.paths.raw / row["src_name"])
        if row["record_kind"] == "bundle":
            # 打包目录：把原图还给 raw，整包删掉（text.md 是派生物）
            img = rec / row["src_name"]
            if img.exists():
                library.transfer(img, back, "move")
            leftover = [p for p in rec.iterdir() if p.name != "text.md"]
            if leftover:
                print(f"打包目录里还有其它文件，保留目录：{rec}", file=sys.stderr)
                store.update_item(args.id, status="undone", error="打包目录未清空")
            else:
                (rec / "text.md").unlink(missing_ok=True)
                try:
                    rec.rmdir()
                except OSError:
                    pass
        else:
            library.transfer(rec, back, "move")
        store.delete_todos_of_item(args.id)
        store.update_item(args.id, status="undone", record_path=None, todo_count=0)
        library.rebuild_todos(cfg, store)
        print(f"已退回：{back}（相关待办已一并撤下）")
    finally:
        store.close()
    return 0


def cmd_ocr(cfg: Config, args) -> int:
    p = Path(args.file)
    if not p.is_file():
        print(f"找不到文件：{p}", file=sys.stderr)
        return 1
    info = None
    kind, doc_date, info, extra = describe(p)
    if info is not None:
        print(f"图片：{info.width}x{info.height} {info.fmt}"
              f"{' · 相机 ' + info.camera if info.camera else ''}", file=sys.stderr)
    services.ensure(cfg, need_llm=False, need_ocr=True)
    res = vision.run_ocr(p, cfg, prompt=args.prompt)
    if res.error:
        print(f"OCR 失败：{res.error}", file=sys.stderr)
        return 1
    print(res.text)
    print(f"\n（{res.elapsed:.1f}s，{len(res.text)} 字，引擎 {res.engine}）", file=sys.stderr)
    return 0


def cmd_extract(cfg: Config, args) -> int:
    p = Path(args.file)
    if not p.is_file():
        print(f"找不到文件：{p}", file=sys.stderr)
        return 1
    kind, doc_date, info, extra = describe(p)
    if p.suffix.lower() in READABLE_TEXT_EXT:
        body = read_text_best_effort(p)
    else:
        services.ensure(cfg, need_llm=False, need_ocr=True)
        res = vision.run_ocr(p, cfg)
        if res.error:
            print(f"OCR 失败：{res.error}", file=sys.stderr)
            return 1
        body = res.text
    services.ensure(cfg, need_llm=True, need_ocr=False)
    t = todos_mod.extract(source_name=p.name, doc_date=doc_date, body_text=body, cfg=cfg)
    out = {
        "source": p.name,
        "doc_date": doc_date,
        "today": _dt.date.today().isoformat(),
        "passes": t.passes,
        "notes": t.notes,
        "todos": [{"task": e.task, "start": e.start, "end": e.end,
                   "span_days": e.span_days, "votes": e.votes,
                   "via": e.source_pass, "evidence": e.evidence} for e in t.entries],
        "没写日期而丢弃": t.dropped_no_date,
        "日期与自己抄的依据脱钩、丢弃": t.dropped_unbound,
        "材料无时间词、日期判为编造而丢弃": t.dropped_no_anchor,
        "日期不合理而丢弃": t.dropped_insane,
        "error": t.error,
    }
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0 if not t.error else 1


def cmd_reindex(cfg: Config, args) -> int:
    store = _store(cfg)
    try:
        info = library.rebuild_todos(cfg, store)
    finally:
        store.close()
    print(f"已重建 {cfg.paths.todos}：{info['days']} 个日期目录 · {info['todos']} 条待办")
    return 0


def cmd_reextract(cfg: Config, args) -> int:
    """模型或提示词改好后，用来修正历史条目的待办（不动 records/ 里的文件）。"""
    store = _store(cfg)
    try:
        if args.all:
            rows = store.conn.execute(
                "SELECT * FROM items WHERE status='filed' ORDER BY id").fetchall()
        elif args.ids:
            rows = [r for r in (store.get_item(i) for i in args.ids) if r is not None]
        else:
            print("要么给 id，要么加 --all", file=sys.stderr)
            return 2
        if not rows:
            print("没有可重抽的条目")
            return 0
        names = ", ".join(f"#{r['id']}" for r in rows)
        print(f"重抽 {len(rows)} 条（{names}）"
              f"{' [演练，不写入]' if args.dry_run else ''}")
        out = pipeline.reextract(cfg, store, rows, dry_run=args.dry_run,
                                 verbose=not args.quiet, fast=args.fast)
    finally:
        store.close()
    print(f"\n待办：{out['before']} 条 → {out['after']} 条"
          f"（跳过 {out['skipped']} 条取不到正文的）")
    if args.dry_run:
        print("（演练模式，什么都没改；去掉 --dry-run 才生效）")
    return 0


def cmd_models(cfg: Config, args) -> int:
    if args.action == "status":
        for name, s in services.status(cfg).items():
            print(f"{name:<5} {'✅ 就绪' if s.up else '❌ 不可用'}  {s.detail}")
        return 0
    if args.action == "start":
        out = []
        if args.which in ("all", "llm"):
            out.append(services.start_llm(cfg))
        if args.which in ("all", "ocr"):
            out.append(services.start_ocr(cfg))
        for s in out:
            print(f"{s.name:<5} {'✅' if s.up else '❌'}  {s.detail}")
        return 0 if all(s.up for s in out) else 1
    ok = services.stop_ocr(cfg) if args.which in ("all", "ocr") else False
    print(f"OCR 服务{'已停止' if ok else '本来就没跑（或不是本程序拉起的）'}")
    if args.which in ("all", "llm"):
        print("Qwen3-4B 由 `llm` 脚本管理，停它请用：llm stop")
    return 0


def cmd_trigger(cfg: Config, args) -> int:
    """给 SSH 网关调用的入口：立刻返回，把归档丢到后台去做。

    手机上传完就调它，所以不能让手机等几分钟。真正的归档由后台的
    `inbox run --lock` 完成，它自己会先等一下再扫（避开还没写完的文件）。
    """
    project = Path(__file__).resolve().parent.parent
    launcher = project / "bin" / "inbox"
    cfg.paths.state_dir.mkdir(parents=True, exist_ok=True)
    logf = cfg.trigger_log_path.open("ab")
    logf.write(f"\n===== {_dt.datetime.now():%Y-%m-%d %H:%M:%S} 收到触发（来自 {args.peer or '未知'}）\n"
               .encode())
    logf.flush()
    cmd = [sys.executable, str(launcher), "run", "--lock", "-q"]
    if args.no_llm:
        cmd.append("--no-llm")
    if args.no_ocr:
        cmd.append("--no-ocr")
    try:
        subprocess.Popen(  # noqa: S603
            cmd, stdout=logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            start_new_session=True, cwd=str(project))
    except Exception as exc:  # noqa: BLE001
        print(f"QUEUE-FAILED {exc}")
        logf.close()
        return 1
    logf.close()
    print("QUEUED 已开始归档，服务器稍后会在 archives 下出现结果")
    return 0


def cmd_doctor(cfg: Config, args) -> int:
    print(f"版本      {__version__}")
    print(f"配置      {cfg.source}")
    print()
    print(f"原文件    {cfg.paths.raw}   {'存在' if cfg.paths.raw.is_dir() else '❌ 不存在'}"
          f"  可写={_writable(cfg.paths.raw)}")
    print(f"档案      {cfg.paths.records}   {'存在' if cfg.paths.records.is_dir() else '（将由本程序创建）'}"
          f"  可写={_writable(cfg.paths.records)}")
    print(f"事项      {cfg.paths.todos}   {'存在' if cfg.paths.todos.is_dir() else '（将由本程序创建）'}"
          f"  可写={_writable(cfg.paths.todos)}")
    print(f"状态目录  {cfg.paths.state_dir}  可写={_writable(cfg.paths.state_dir)}")
    print()
    print(f"OCR 模式  {cfg.models.ocr_mode} · 非文本/图片 {cfg.behavior.foreign_action} · "
          f"模型不可用 {cfg.behavior.on_model_error}")
    print(f"触发延时  {cfg.behavior.trigger_delay_seconds}s · 稳定窗口 {cfg.filters.min_stable_seconds}s")
    print(f"CUDA      {services.gpu_detail()}")
    for name, s in services.status(cfg).items():
        print(f"{name:<9} {'✅ 就绪' if s.up else '❌ 不可用'}  {s.detail}")
    try:
        import PIL  # noqa: F401
        print("Pillow    ✅ 可用（能读 EXIF / 尺寸）")
    except Exception as exc:  # noqa: BLE001
        print(f"Pillow    ❌ 不可用：{exc}")
    sc = scan(cfg)
    print(f"raw/ 现状 待处理 {len(sc.ready)} · 传着/太新 {len(sc.waiting)} · "
          f"忽略 {len(sc.ignored)} · 非文本图片 {len(sc.foreign)}")
    return 0


def _writable(p: Path) -> str:
    probe = p if p.is_dir() else p.parent
    if not probe.exists():
        return "父目录不存在"
    return "是" if os.access(probe, os.W_OK) else "否"


# ---------------------------------------------------------------- 入口


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog="inbox",
        description="手机存档区 → 本地模型整理归档（raw → records → todos）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""示例：
  inbox doctor                            环境自检
  inbox status                            看 raw/ 里有什么、待办覆盖哪些天
  inbox run --dry-run                     只演练，不动文件
  inbox run                               真正归档（模型没起会自动拉起）
  inbox todos --from 2026-10-01 --to 2026-10-31    读某一段时间的待办
  inbox todos --rebuild                   从状态库重建 todos/年.月.日/
  inbox extract ~/x.txt                   只跑一遍待办抽取，看模型怎么判
  inbox undo 12                           把第 12 条从档案退回 raw/
""")
    ap.add_argument("--config", help="配置文件路径（默认 ./config.toml）")
    ap.add_argument("--version", action="version", version=f"inbox {__version__}")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("run", help="扫描 raw/ 并归档")
    p.add_argument("--dry-run", action="store_true", help="只显示计划，不动任何文件")
    p.add_argument("--limit", type=int, default=0, help="本次最多处理几个")
    p.add_argument("--only", action="append", default=[], help="只处理路径/文件名含该串的文件")
    p.add_argument("--copy", action="store_true", help="复制而非移动原件")
    p.add_argument("--no-ocr", action="store_true", help="本次不做图片 OCR")
    p.add_argument("--no-llm", action="store_true", help="本次不抽待办")
    p.add_argument("--no-preheat", action="store_true",
                   help="本次不做热启动（默认大图 OCR 前会先用 2 线程把温度抬到"
                        "目标值，让 EC 的风扇提前带上工作点）")
    p.add_argument("--force", action="store_true", help="模型不可用也照样归档（抽不出待办）")
    p.add_argument("--allow-duplicate", action="store_true", help="内容重复的也重新归档")
    p.add_argument("--fast", action="store_true",
                   help="待办只问一次模型（快；默认是多路抽取+复核，为准确率花时间）")
    p.add_argument("--from", dest="source", help="换个来源目录跑一次（如旧的 ~/phone-upload）")
    p.add_argument("--lock", action="store_true", help="加独占锁（同一时间只跑一轮）")
    p.add_argument("--delay", type=int, default=None, help="加锁后先等 N 秒再扫（默认取配置）")
    p.add_argument("-q", "--quiet", action="store_true")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("status", help="raw/、档案库、待办与模型状态")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("todos", help="读待办（可按时间段）")
    p.add_argument("--from", dest="start", help="起始日期 YYYY-MM-DD（含）")
    p.add_argument("--to", dest="end", help="结束日期 YYYY-MM-DD（含）")
    p.add_argument("--month", help="整月，如 2026-10 或 2026.10")
    p.add_argument("--last", type=int, help="最近 N 天（含今天）")
    p.add_argument("--rebuild", action="store_true", help="先从状态库重建 todos/ 目录")
    p.add_argument("--json", action="store_true", help="输出 JSON Lines")
    p.add_argument("--show-source", action="store_true", help="每条后面带上来源文件名")
    p.add_argument("--active", action="store_true",
                   help="只看还没办结的（已完成 ✅ / 已取消 ❌ 的滤掉）")
    p.set_defaults(func=cmd_todos)

    p = sub.add_parser("list", help="历史处理记录")
    p.add_argument("-n", "--number", type=int, default=20)
    p.add_argument("--status", help="filed/foreign/duplicate/failed/deferred/undone")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("undo", help="把某条从档案退回 raw/（连带撤下它的待办）")
    p.add_argument("id", type=int)
    p.set_defaults(func=cmd_undo)

    p = sub.add_parser("ocr", help="单独 OCR 一张图（调试）")
    p.add_argument("file")
    p.add_argument("-p", "--prompt", default=None)
    p.set_defaults(func=cmd_ocr)

    p = sub.add_parser("extract", help="单独跑一遍待办抽取（调试）")
    p.add_argument("file")
    p.set_defaults(func=cmd_extract)

    p = sub.add_parser("reindex", help="从状态库重建 todos/ 目录")
    p.set_defaults(func=cmd_reindex)

    p = sub.add_parser("reextract",
                       help="对已归档条目重跑待办抽取（模型/提示词改好后修正历史）")
    p.add_argument("ids", nargs="*", type=int, help="条目 id（不给就配 --all）")
    p.add_argument("--all", action="store_true", help="全部已归档条目")
    p.add_argument("--dry-run", action="store_true", help="只看结果，不写入")
    p.add_argument("--fast", action="store_true", help="只问一次模型（默认多路+复核）")
    p.add_argument("-q", "--quiet", action="store_true")
    p.set_defaults(func=cmd_reextract)

    p = sub.add_parser("models", help="模型服务")
    p.add_argument("action", choices=["status", "start", "stop"])
    p.add_argument("--which", choices=["all", "llm", "ocr"], default="all")
    p.set_defaults(func=cmd_models)

    p = sub.add_parser("trigger", help="（给 SSH 网关用）后台开始一轮归档，立刻返回")
    p.add_argument("--peer", default="", help="调用方标识，只用于日志")
    p.add_argument("--no-llm", action="store_true")
    p.add_argument("--no-ocr", action="store_true")
    p.set_defaults(func=cmd_trigger)

    p = sub.add_parser("doctor", help="环境自检")
    p.set_defaults(func=cmd_doctor)
    return ap


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    cfg = load(args.config)
    quiet = getattr(args, "quiet", False)
    _setup_logging(cfg, quiet)
    if args.cmd == "run":
        log.info("inbox run 开始")
    try:
        rc = args.func(cfg, args)
    except KeyboardInterrupt:
        print("\n已中断", file=sys.stderr)
        return 130
    except Exception as exc:  # noqa: BLE001
        log.exception("执行失败")
        print(f"执行失败：{exc}", file=sys.stderr)
        return 1
    if args.cmd == "run":
        log.info("inbox run 结束 rc=%s", rc)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
