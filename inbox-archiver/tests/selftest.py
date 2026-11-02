#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线自检：不碰模型服务、不碰真实归档库，全程在临时目录里跑。

    python3 tests/selftest.py

覆盖：日期解析 / 文件名清洗 / 类型判定 / EXIF / 打包目录 /
      text.md / 待办日期归一与跨日铺开 / 状态库 / 整轮流程（文本+图片+异物+重复+回退）/
      dry-run 不动文件。
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT))

from archiver import library, pipeline, todos as todos_mod, vision   # noqa: E402
from archiver.config import Config                                   # noqa: E402
from archiver.pipeline import Options, scan                          # noqa: E402
from archiver.store import Store, FILED, FOREIGN                     # noqa: E402
from archiver.util import (date_from_filename, detect_kind, human_size,   # noqa: E402
                           read_image_info, safe_name)

PASS, FAIL = 0, 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ✓ {name}")
    else:
        FAIL += 1
        print(f"  ✗ {name}  {detail}")


def section(title: str) -> None:
    print(f"\n[{title}]")


def make_png(path: Path, size=(1080, 2340), exif: dict | None = None) -> Path:
    from PIL import Image
    im = Image.new("RGB", size, (240, 240, 245))
    if exif:
        ex = Image.Exif()
        for k, v in exif.items():
            ex[k] = v
        im.save(path, exif=ex)
    else:
        im.save(path)
    return path


def main() -> int:
    (PROJECT / "run").mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="inbox-selftest-", dir=str(PROJECT / "run")))
    raw = tmp / "raw"
    records = tmp / "records"
    todos_dir = tmp / "todos"
    raw.mkdir(parents=True)
    print(f"临时目录：{tmp}")

    try:
        # ---------------------------------------------------------- util
        section("文件名里的日期")
        cases = {
            "Screenshot_2027-04-05-16-30-00-42_0dff.jpg": "2027-04-05 16:30:00",
            "IMG20270406071500.jpg": "2027-04-06 07:15:00",
            "VID20270407193045.mp4": "2027-04-07 19:30:45",
            "Record_2027-04-08-09-10-11_a1b2.mp4": "2027-04-08 09:10:11",
            "IMG_20270409_120000.jpg": "2027-04-09 12:00:00",
            "text-1807315200000.txt": "2027-04-10 08:00:00",
            "聊天记录.txt": None,
        }
        for name, want in cases.items():
            got = date_from_filename(name)
            if want is None:
                check(f"{name} → 安全返回 None", got is None, f"实际 {got}")
            else:
                ok = got is not None and got.strftime("%Y-%m-%d %H:%M:%S") == want
                check(f"{name} → {want}", ok, f"实际 {got}")

        section("类型判定 / 大小 / 文件名清洗")
        check("jpg → image", detect_kind("a.JPG") == "image")
        check("txt → text", detect_kind("a.txt") == "text")
        check("mp4 → video", detect_kind("a.mp4") == "video")
        check("unknown → other", detect_kind("a.zzz") == "other")
        check("human_size", human_size(1536).endswith("KB"), human_size(1536))
        check("清洗路径分隔符", "/" not in safe_name("a/b\\c:d"))
        check("去掉标题里的后缀", safe_name("会议纪要.jpg") == "会议纪要")
        check("空标题有兜底", safe_name("", fallback="x") == "x")
        check("超长截断", len(safe_name("啊" * 100, 10)) <= 10)

        # ---------------------------------------------------------- 图片
        section("图片元数据")
        photo = make_png(raw / "IMG20270406071500.jpg", (4000, 3000), exif={
            271: "Shottest\x00\x00", 272: "Shottest Ace Run\x00\x00",
            36867: "2027:04:06 07:15:00"})
        shot = make_png(raw / "Screenshot_2027-04-05-16-30-00-42_abc.jpg", (1080, 2340))
        info_shot = read_image_info(shot)
        info_photo = read_image_info(photo)
        check("读到截图尺寸", (info_shot.width, info_shot.height) == (1080, 2340))
        check("截图无相机信息", not info_shot.is_camera_photo)
        check("EXIF 尾部 \\x00 被清掉", info_photo.camera == "Shottest Ace Run",
              repr(info_photo.camera))
        check("读到拍摄时间",
              info_photo.exif_datetime == _dt.datetime(2027, 4, 6, 7, 15, 0),
              str(info_photo.exif_datetime))

        # ---------------------------------------------------------- 归档布局
        section("records/ 布局")
        cfg = Config()
        cfg.paths.root = tmp
        cfg.paths.raw = raw
        cfg.paths.records = records
        cfg.paths.todos = todos_dir
        cfg.paths.state_dir = tmp / "state"
        cfg.paths.state_dir.mkdir(parents=True)
        cfg.filters.min_stable_seconds = 0
        cfg.models.llm_port = 18080
        cfg.models.ocr_port = 18083
        cfg.models.autostart = False

        t = library.text_record_path(records, "笔记.txt")
        check("文本路径保留原名", t.name == "笔记.txt", t.name)
        t.parent.mkdir(parents=True, exist_ok=True)
        t.write_text("x", encoding="utf-8")
        check("文本同名自动加 -2",
              library.text_record_path(records, "笔记.txt").name == "笔记-2.txt")
        b = library.image_bundle_dir(records, "Screenshot_2027-04-05-16-30-00-42_abc.jpg")
        check("图片打包目录用原名去后缀",
              b.name == "Screenshot_2027-04-05-16-30-00-42_abc", b.name)
        b.mkdir(parents=True, exist_ok=True)
        check("打包目录同名加 -2",
              library.image_bundle_dir(records, "Screenshot_2027-04-05-16-30-00-42_abc.jpg").name
              == "Screenshot_2027-04-05-16-30-00-42_abc-2")

        section("text.md 与日期目录名")
        b2 = records / "bundle-x"
        b2.mkdir(parents=True)
        tp = library.write_bundle_text(b2, source_name="a.jpg", size=10, sha256="ab",
                                       doc_date="2027-04-05", ocr_engine="paddleocr-vl",
                                       ocr_text="识别出来的文字", extra={"拍摄设备": "X"})
        body = tp.read_text(encoding="utf-8")
        check("text.md 有 OCR 段", "## OCR 文本" in body and "识别出来的文字" in body)
        check("text.md 带来源元数据", "source_name: a.jpg" in body)
        check("text.md 带额外元数据", "拍摄设备：X" in body)
        # 重抽待办要能原样取回当初喂给模型的那段文字（不能把 front matter 也带上）
        check("read_bundle_text 取回纯 OCR 原文",
              library.read_bundle_text(tp) == "识别出来的文字",
              repr(library.read_bundle_text(tp)))
        check("2026-10-14 → 2026.10.14",
              library.day_dir_name("2026-10-14") == "2026.10.14")
        check("解析日期目录", library.parse_day_dir("2026.10.14") == "2026-10-14")
        check("非日期目录不认", library.parse_day_dir("未标注日期") is None)
        check("非法日期不认", library.parse_day_dir("2026.13.40") is None)

        # ---------------------------------------------------------- store
        section("状态库")
        store = Store(cfg.db_path)
        iid = store.add_item(src_name="a.txt", src_path="/x/a.txt", size=1, mtime=1.0,
                             sha256="aa", kind="text", status=FILED,
                             created_at="now", updated_at="now")
        check("按 sha 查到已归档", store.by_sha("aa") is not None)
        check("查不到的 sha 返回 None", store.by_sha("zz") is None)
        check("状态统计", store.item_counts().get(FILED) == 1, str(store.item_counts()))
        store.add_todo(item_id=iid, source_name="a.txt", record_path="/x/a.txt",
                       task="跨日的事", start_date="2026-10-15", end_date="2026-10-17",
                       evidence="e", created_at="now")
        check("区间查询命中跨日事项",
              len(store.todos_in_range("2026-10-16", "2026-10-16")) == 1)
        check("区间外的查不到",
              len(store.todos_in_range("2026-10-18", "2026-10-20")) == 0)
        check("按天查", len(store.todos_for_day("2026-10-15")) == 1)
        check("todo_days 铺开 3 天", store.todo_days() ==
              ["2026-10-15", "2026-10-16", "2026-10-17"], str(store.todo_days()))

        section("todos/：事项单文件 + 时间文件夹只放地址")
        info = library.rebuild_todos(cfg, store)
        check("生成 3 个日期目录", info["days"] == 3, str(info))
        check("事项文件数 = 事项数", info["items"] == 1, str(info))
        items_dir = todos_dir / "items"
        files = sorted(x.name for x in items_dir.glob("*.md"))
        check("事项文件夹里一件一个文件", files == ["20261015~20261017-跨日的事.md"], str(files))
        item_txt = (items_dir / "20261015~20261017-跨日的事.md").read_text(encoding="utf-8")
        check("事项文件带日期与来源", "start_date: 2026-10-15" in item_txt
              and "end_date: 2026-10-17" in item_txt and "source_name: a.txt" in item_txt)
        check("事项文件有备注节（可手写）", library.NOTE_MARKER in item_txt)
        check("事项文件有一致度", "votes: 1" in item_txt)

        d = todos_dir / "2026.10.16"
        check("目录名是年.月.日", d.is_dir(), str(d))
        md = (d / "items.md").read_text(encoding="utf-8")
        check("时间文件夹里只有地址（markdown 链接）",
              md.count("- [") == 1 and "../items/20261015~20261017-跨日的事.md" in md, md)
        check("时间文件夹里不再重复事项正文",
              "进行中" not in md and "依据" not in md, md)
        txt = (d / "items.txt").read_text(encoding="utf-8")
        check("items.txt 是纯地址",
              txt.strip() == "../items/20261015~20261017-跨日的事.md", repr(txt))
        check("index.jsonl 存在", (todos_dir / "index.jsonl").is_file())
        lines = (todos_dir / "index.jsonl").read_text(encoding="utf-8").strip().splitlines()
        check("index.jsonl 1 行", len(lines) == 1, str(len(lines)))
        check("index.jsonl 带事项文件地址",
              "items/20261015~20261017-跨日的事.md" in lines[0], lines[0])

        # 用户写进「## 备注」的内容必须活过重建
        noted = items_dir / "20261015~20261017-跨日的事.md"
        noted.write_text(noted.read_text(encoding="utf-8") + "\n我自己的进度：还没开始。\n",
                         encoding="utf-8")
        library.rebuild_todos(cfg, store)
        check("重建保留用户备注",
              "我自己的进度：还没开始。" in noted.read_text(encoding="utf-8"))
        # 事项被撤下：有备注的进 _已撤下/，纯生成物直接删
        store.add_todo(item_id=1, source_name="a.txt", record_path="/x",
                       task="临时事项", start_date="2026-11-01", end_date="2026-11-01",
                       evidence="", created_at="now")
        library.rebuild_todos(cfg, store)
        tmp_file = items_dir / "20261101-临时事项.md"
        check("新事项生成文件", tmp_file.is_file())
        store.conn.execute("DELETE FROM todos WHERE task='临时事项'")
        store.conn.commit()
        library.rebuild_todos(cfg, store)
        check("没备注的撤下事项被删掉", not tmp_file.exists())
        check("有备注的撤下事项被保留", noted.is_file())
        # 用户自己放进去的目录不能被误删
        (todos_dir / "我的笔记").mkdir()
        library.rebuild_todos(cfg, store)
        check("重建不会误删非日期目录", (todos_dir / "我的笔记").is_dir())

        # ---------------------------------------------------------- todos 归一
        section("待办日期归一与丢弃规则")
        check("规范日期", todos_mod._norm_date("2026-10-14") == "2026-10-14")
        check("斜杠日期", todos_mod._norm_date("2026/10/14") == "2026-10-14")
        check("点号日期", todos_mod._norm_date("2026.10.14") == "2026-10-14")
        check("补零", todos_mod._norm_date("2026-1-4") == "2026-01-04")
        check("空字符串 → None", todos_mod._norm_date("") is None)
        check("胡说的日期 → None", todos_mod._norm_date("下周五") is None)
        check("不存在的日期 → None", todos_mod._norm_date("2026-02-30") is None)
        check("抠出裸 JSON", todos_mod._extract_json('{"todos":[]}') == {"todos": []})
        check("抠出带代码块的 JSON",
              todos_mod._extract_json('```json\n{"a": 2}\n```') == {"a": 2})
        check("非法 JSON → None", todos_mod._extract_json("不是 JSON") is None)

        section("时间词护栏（模型编日期的手术刀）")
        check("有绝对日期", todos_mod.has_temporal_reference("请于10月20日前提交"))
        check("有相对说法", todos_mod.has_temporal_reference("下周一把材料带来"))
        check("有点号日期", todos_mod.has_temporal_reference("截止 2026-10-20"))
        check("有月日", todos_mod.has_temporal_reference("10月15日至10月18日检查"))
        check("纯要求没时间词", not todos_mod.has_temporal_reference(
            "请同学们按照要求及时完成自查工作。谢谢！"))
        check("纯闲聊没时间词", not todos_mod.has_temporal_reference(
            "天气不错，操场人多，饭菜也还行。"))
        check("点号日期能识别（10.31）",
              todos_mod.has_temporal_reference("秋季联赛将于10.31下午举行"))
        check("斜杠日期能识别（11/20）",
              todos_mod.has_temporal_reference("活动定于 11/20 举行"))
        check("10.31 会进时间点清单",
              any("10.31" in m for m in todos_mod.time_mentions("秋季联赛将于10.31下午举行")))
        check("带单位的小数不当日期（清单）",
              not any("12.5" in m for m in todos_mod.time_mentions("价格是 12.5 元")))
        check("比值不当日期", not todos_mod.has_temporal_reference("比值 25/4 很大"))

        today = _dt.date.today()
        near = (today + _dt.timedelta(days=3)).isoformat()
        far = (today + _dt.timedelta(days=40)).isoformat()
        res = todos_mod._normalize({"todos": [
            {"task": "有日期的", "start": near, "end": near, "evidence": "e1"},
            {"task": "没日期的", "start": "", "end": "", "evidence": "e2"},
            {"task": "跨日的", "start": near, "end": far, "evidence": "e3"},
            {"task": "日期颠倒的", "start": far, "end": near, "evidence": "e4"},
            {"task": "", "start": near, "end": near},
            {"task": "远古的", "start": "1999-01-01", "end": "1999-01-01"},
        ]}, cfg, raw="", model="m")
        check("没日期的被丢掉", res.dropped_no_date == 1, str(res.dropped_no_date))
        check("远古的被丢掉", res.dropped_insane == 1, str(res.dropped_insane))
        check("空 task 被丢掉", len(res.entries) == 3, str([e.task for e in res.entries]))
        swap = [e for e in res.entries if e.task == "日期颠倒的"][0]
        check("日期颠倒会自动交换", (swap.start, swap.end) == (near, far),
              f"{swap.start}~{swap.end}")
        span = [e for e in res.entries if e.task == "跨日的"][0]
        check("跨日天数计算", span.span_days == 38, str(span.span_days))

        section("多路一致度聚类（离线，不调模型）")
        def mk(pairs, name="x"):
            return todos_mod.TodoResult(
                entries=[todos_mod.TodoEntry(task=t, start=s_, end=e_)
                         for t, s_, e_ in pairs], passes=1, model=name)
        drafts = [
            mk([("参加秋季联赛", "2026-10-31", "2026-10-31")]),                    # 第 1 路
            mk([("参加秋季联赛比赛", "2026-10-31", "2026-10-31"),
                ("交实验报告", "2026-10-20", "2026-10-20")]),                    # 第 2 路
            mk([("参加秋季联赛", "2026-10-31", "2026-10-31")]),                    # 第 3 路
        ]
        cl = todos_mod.cluster_drafts(drafts, 0.55)
        check("同一件事跨 3 路聚成一簇", len(cl) == 2, [c.task for c in cl])
        hot = [c for c in cl if c.votes == 3]
        check("三路都抽到的那条票数=3", len(hot) == 1, [(c.task, c.votes) for c in cl])
        check("票数高的排前面", cl[0].votes == 3, [(c.task, c.votes) for c in cl])
        # 近义变体里取信息最全的那条（"参加秋季联赛比赛" 比 "参加秋季联赛" 完整）
        check("近义变体取信息最全的", cl[0].task == "参加秋季联赛比赛", cl[0].task)
        check("单路独有的票数=1", any(c.votes == 1 for c in cl))
        check("日期不同不会合并",
              len([c for c in cl if c.start == "2026-10-20"]) == 1)

        # 错绑：一路说「参加」一路说「报名」，票数并列时取主提示词（第 1 路）那条
        tie = todos_mod.cluster_drafts([
            mk([("参加秋季联赛", "2026-10-31", "2026-10-31")]),
            mk([("报名秋季联赛", "2026-10-31", "2026-10-31")]),
        ], 0.55)
        check("「参加」与「报名」会聚成同一簇（判给复核去裁）", len(tie) == 1,
              [c.task for c in tie])
        check("票数并列时取主提示词那条（参加）", tie[0].task == "参加秋季联赛", tie[0].task)
        abbr = todos_mod.cluster_drafts([
            mk([("汇总报名名单", "2026-11-05", "2026-11-05")]),
            mk([("报名", "2026-11-05", "2026-11-05")]),
            mk([("报名", "2026-11-05", "2026-11-05")]),
        ], 0.7)
        check("缩写与完整说法同簇，取完整的那条",
              len(abbr) == 1 and abbr[0].task == "汇总报名名单", [c.task for c in abbr])
        check("并列也算 2 路一致", tie[0].votes == 2, tie[0].votes)
        check("同一路内部不合并",
              len(todos_mod.cluster_drafts([mk([("甲事", "2026-10-01", "2026-10-01"),
                                                ("甲事", "2026-10-01", "2026-10-01")])],
                                           0.55)) == 2)

        # ── 收尾（consolidate_clusters）：同一路自己吐的重复、空泛/系列标题 ──
        dup = todos_mod.cluster_drafts([
            mk([("报名航模课程", "2026-10-14", "2026-10-14"),
                ("报名航模课程", "2026-10-14", "2026-10-15")]),   # 同一路吐了两条
        ], 0.6)
        check("同一路吐出的重复确实先分成两簇", len(dup) == 2, [c.task for c in dup])
        merged_dup = todos_mod.consolidate_clusters(dup, 0.6)
        check("收尾把同一路自己吐的重复合成一条",
              len(merged_dup) == 1 and merged_dup[0].votes == 1,
              [(c.task, c.votes, c.start, c.end) for c in merged_dup])

        blanket = todos_mod.cluster_drafts([
            mk([("参加10月14日的讲座", "2026-10-14", "2026-10-14")]),
            mk([("参加从选题到开题——文献检索实操", "2026-10-14", "2026-10-14")]),
            mk([("参加11月23日的甲、乙、丙、丁等专场讲座",
                 "2026-10-23", "2026-10-23")]),
            mk([("参加三维建模软件新功能培训", "2026-10-23", "2026-10-23")]),
        ], 0.6)
        kept = todos_mod.consolidate_clusters(blanket, 0.6)
        check("收尾丢掉空泛说法，留下点名的那条",
              len(kept) == 2 and all("讲座" not in c.task or "三维建模" in c.task
                                     for c in kept), [c.task for c in kept])
        check("系列名（…等专场讲座）也算空泛标题",
              todos_mod._blanket_task("参加11月23日的甲、乙、丙、丁等专场讲座"))
        check("「参加某日××专场讲座」这类弱标题也算空泛",
              todos_mod._blanket_task("参加11月14日表格排版专场讲座"))
        check("点名到具体活动的标题不算空泛",
              not todos_mod._blanket_task("参加三维建模软件新功能培训"))
        lone = todos_mod.consolidate_clusters(todos_mod.cluster_drafts([
            mk([("参加10月14日的讲座", "2026-10-14", "2026-10-14")]),
        ], 0.6), 0.6)
        check("同一天只剩空泛那条时留着（材料本身没给名字）",
              len(lone) == 1, [c.task for c in lone])

        # 空泛的票多、具体的票少：不能互相把对方判掉（否则整天条目一起消失）
        cascade = todos_mod.consolidate_clusters(todos_mod.cluster_drafts([
            mk([("参加10月14日的讲座", "2026-10-14", "2026-10-14")]),
            mk([("参加10月14日的讲座", "2026-10-14", "2026-10-14")]),
            mk([("参加文献检索实操", "2026-10-14", "2026-10-14")]),
        ], 0.6), 0.6)
        check("空泛票多、具体票少时，留下具体的那条（不互相判掉）",
              len(cascade) == 1 and "文献检索" in cascade[0].task,
              [(c.task, c.votes) for c in cascade])

        # 收尾第 1 刀的新口径：空泛那条**并进**具体那条，不是整条扔掉
        check("「报名课程」这种两个字标题也算空泛", todos_mod._blanket_task("报名课程"))
        joint = todos_mod.consolidate_clusters(todos_mod.cluster_drafts([
            mk([("参加课程", "2026-10-15", "2026-10-15")]),
            mk([("参加课程", "2026-10-15", "2026-10-15")]),
            mk([("参加航模系统设计与实战课程", "2026-10-15", "2026-10-15")]),
        ], 0.6), 0.6)
        check("空泛那条并进同日的具体那条：名字用点得出全名的、票数按并集",
              len(joint) == 1 and joint[0].task == "参加航模系统设计与实战课程"
              and joint[0].votes == 3, [(c.task, c.votes) for c in joint])
        diffact = todos_mod.consolidate_clusters(todos_mod.cluster_drafts([
            mk([("报名课程", "2026-10-14", "2026-10-15")]),
            mk([("参加三维建模软件新功能培训", "2026-10-14", "2026-10-14")]),
        ], 0.6), 0.6)
        check("空泛「报名」不会去并同日的「参加」那条（不是同一类动作）",
              len(diffact) == 2, [(c.task, c.votes) for c in diffact])
        twin_a = todos_mod._Cluster(passes=4, members=[
            (i, todos_mod.TodoEntry(task="参加课程", start="2026-10-18", end="2026-10-18",
                                    date_before="2026-10-15")) for i in (0, 1, 2)])
        twin_b = todos_mod._Cluster(passes=4, members=[
            (3, todos_mod.TodoEntry(task="参加航模系统设计与实战课程",
                                    start="2026-10-15", end="2026-10-15"))])
        twin = todos_mod.consolidate_clusters([twin_a, twin_b], 0.6)
        check("护栏把空泛那条的日期搬走之后，收尾仍按旧日期认出是同一件事",
              len(twin) == 1 and twin[0].task == "参加航模系统设计与实战课程"
              and twin[0].start == "2026-10-18" and twin[0].votes == 4,
              [(c.task, c.votes, c.start) for c in twin])


        noise = todos_mod.cluster_drafts([
            mk([("参加工具赋能系列", "2026-10-23", "2026-10-23")]),                 # 1 票
            mk([("参加设计工具链与职业成长路径培训",
                 "2026-10-23", "2026-10-23"),                                      # 2 票
                ("参加一小时搞定表格排版",
                 "2026-10-22", "2026-10-22")]),
            mk([("参加设计工具链与职业成长路径培训",
                 "2026-10-23", "2026-10-23")]),
        ], 0.6)
        kept3 = todos_mod.consolidate_clusters(noise, 0.6)
        check("同一天1票的落单「参加」说法被丢掉",
              len(kept3) == 2 and all("工具赋能系列" not in c.task for c in kept3),
              [(c.task, c.votes) for c in kept3])

        stable = todos_mod.cluster_drafts([
            mk([("提交本班课表", "2026-10-14", "2026-10-14")]),                    # 1 票
            mk([("参加文献检索讲座", "2026-10-14", "2026-10-14"),
                ("参加三维建模培训", "2026-10-14", "2026-10-14")]),
            mk([("参加文献检索讲座", "2026-10-14", "2026-10-14")]),
        ], 0.6)
        kept4 = todos_mod.consolidate_clusters(stable, 0.6)
        check("「提交」类1票条目不被这条规则误伤",
              any("提交本班课表" in c.task for c in kept4),
              [(c.task, c.votes) for c in kept4])


        check("事项文件名格式",
              library.item_filename("2026-10-31", "2026-10-31", "参加秋季联赛") ==
              "20261031-参加秋季联赛.md")
        check("跨日事项文件名带区间",
              library.item_filename("2026-10-15", "2026-10-18", "参加检查") ==
              "20261015~20261018-参加检查.md")
        check("事项文件名清洗非法字符",
              "/" not in library.item_filename("2026-10-31", "2026-10-31", "a/b:c"))

        section("确定性核对：依据是否在材料里 + 日期是否有字面（不花模型调用）")
        body = ("秋季联赛报名通知：\n本次秋季联赛将于10.31（周六）\n）下午举行，"
                "大一、研一、博一新生可以报名参加。")
        es = [todos_mod.TodoEntry(task="参加秋季联赛", start="2026-10-31", end="2026-10-31",
                                  evidence="本次秋季联赛将于10.31（周六）下午举行"),
              todos_mod.TodoEntry(task="编造的", start="2026-12-25", end="2026-12-25",
                                  evidence="材料里根本没有这句话，纯属虚构的内容"),
              todos_mod.TodoEntry(task="相对日期换算", start="2026-10-21", end="2026-10-21",
                                  evidence="下周三到期")]
        todos_mod.ground_entries(es, body)
        check("折行/多括号不影响依据核对（骨架比对）", es[0].evidence_grounded)
        check("日期字面 10.31 能对上", es[0].date_literal)
        check("凭空编的依据会被标出来", not es[1].evidence_grounded)
        check("材料里没有的日期会被标出来", not es[1].date_literal)
        check("相对日期换算出来的日期标为'非字面'（只提示不删）",
              es[2].date_literal is False and es[2].evidence_grounded is False,
              f"{es[2].date_literal} / {es[2].evidence_grounded}")

        section("日期必须钉在自己抄的那句依据上（相对说法由代码换算）")
        # 材料日期 2026-10-14 是周三；本周日 = 10-18，本周五 = 10-16，下周五 = 10-23
        def norm(items, doc="2026-10-14", src="通知说本周日上午9点第一节课"):
            return todos_mod._normalize({"todos": items}, cfg, raw="", model="m",
                                         source_text=src, doc_date=doc)

        ok = norm([{"task": "报名航模课程", "start": "2026-11-03", "end": "2026-11-04",
                    "evidence": "报名表开放时间为11月3日14:00-11月4日23:59"}])
        check("依据里写了日期 → 正常保留",
              len(ok.entries) == 1 and ok.entries[0].start == "2026-11-03"
              and ok.entries[0].redated == "", [vars(e) for e in ok.entries])

        fix = norm([{"task": "参加航模课程", "start": "2026-10-15", "end": "2026-10-15",
                     "evidence": "通知说本周日上午9点第一节课"}])
        check("依据只写「本周日」、模型却填了报名截止日 → 按依据换算成 10-18",
              len(fix.entries) == 1 and fix.entries[0].start == "2026-10-18"
              and fix.entries[0].end == "2026-10-18",
              [vars(e) for e in fix.entries])
        check("换算过程记在 redated 里（事项文件会写明来路）",
              bool(fix.entries[0].redated) and "本周日" in fix.entries[0].redated,
              fix.entries[0].redated)

        gone = norm([{"task": "参加讲座", "start": "2026-11-02", "end": "2026-11-02",
                      "evidence": "讲座在大学生活动中心301举办"}],
                    src="体育节定于10.25举行；讲座在大学生活动中心301举办，欢迎参加。")
        check("依据里既没日期、也没有相对说法 → 这条丢掉，不替它编日期",
              len(gone.entries) == 0 and gone.dropped_unbound == 1
              and gone.dropped_no_anchor == 0,
              f"{[e.task for e in gone.entries]} / {gone.dropped_unbound}"
              f" / {gone.dropped_no_anchor}")

        loose = norm([{"task": "参加例会", "start": "2026-10-23", "end": "2026-10-23",
                       "evidence": "例会在周五下午举行"}], src="例会在周五下午举行")
        check("光杆「周五」两种读法都算数：落在候选里就保留",
              len(loose.entries) == 1, [vars(e) for e in loose.entries])
        wrong = norm([{"task": "参加例会", "start": "2026-10-20", "end": "2026-10-20",
                       "evidence": "例会在周五下午举行"}], src="例会在周五下午举行")
        check("光杆「周五」不替模型改日期：对不上就丢",
              len(wrong.entries) == 0 and wrong.dropped_unbound == 1,
              f"{[e.task for e in wrong.entries]} / {wrong.dropped_unbound}")

        noev = norm([{"task": "交材料", "start": "2026-10-20", "end": "2026-10-20",
                      "evidence": ""}])
        check("没抄依据的不拦（沿用原来的标记逻辑）",
              len(noev.entries) == 1, [vars(e) for e in noev.entries])

        span = norm([{"task": "迎评检查", "start": "2026-10-15", "end": "2026-10-18",
                      "evidence": "检查定于10月15日至18日进行"}], src="检查定于10月15日至18日进行")
        check("「10月15日至18日」这种省月份的区间尾巴认得",
              len(span.entries) == 1 and span.entries[0].end == "2026-10-18",
              [vars(e) for e in span.entries])

        base14 = _dt.date(2026, 10, 14)
        cands, exact = todos_mod.resolve_date_clues("通知说本周日上午9点第一节课", base14)
        check("「本周日」换算成 2026-10-18 且唯一",
              exact == {"2026-10-18": "本周日"}, f"{cands} / {exact}")
        c2, e2 = todos_mod.resolve_date_clues("下周三答辩", base14)
        check("「下周三」换算成 2026-10-21", e2 == {"2026-10-21": "下周三"}, f"{c2} / {e2}")
        c3, e3 = todos_mod.resolve_date_clues("这个月底前交", base14)
        check("「月底」换算成 2026-10-31", e3 == {"2026-10-31": "月底前"} or "2026-10-31" in e3,
              f"{c3} / {e3}")
        c4, e4 = todos_mod.resolve_date_clues("明天上午面试", base14)
        check("「明天」换算成 2026-10-15", list(e4) == ["2026-10-15"], f"{c4} / {e4}")
        c5, e5 = todos_mod.resolve_date_clues("这周末开赛", base14)
        check("「周末」给出去周六周日两个候选，不擅自定一天",
              c5 == {"2026-10-17", "2026-10-18"} and not e5, f"{c5} / {e5}")
        c6, e6 = todos_mod.resolve_date_clues("本周例会", base14)
        check("光说「本周」是一整周的范围：能核对不能换算",
              len(c6) == 7 and not e6, f"{sorted(c6)} / {e6}")

        cl_red = todos_mod._Cluster(members=[(0, todos_mod.TodoEntry(
            task="参加航模课程", start="2026-10-18", end="2026-10-18",
            evidence="通知说本周日上午9点第一节课", redated="参加航模课程：2026-10-15→2026-10-18"))])
        check("换算记录能穿过聚类到达事项文件",
              "2026-10-15→2026-10-18" in cl_red.entry().redated, cl_red.entry().redated)

        section("状态通知：材料说「已完成 / 已取消」时落到已有待办上")
        check("通知能匹配上同名待办",
              todos_mod.match_close(
                  todos_mod.CloseSignal(task="摄影培训取消", state="cancelled"),
                  [{"task": "参加春季摄影培训"}]) is not None)
        check("通知里的动作与待办不同类（报名 vs 参加）时不乱关",
              todos_mod.match_close(
                  todos_mod.CloseSignal(task="报名摄影培训已完成"),
                  [{"task": "参加摄影培训"}]) is None)
        check("完全不相干的通知不关任何东西",
              todos_mod.match_close(
                  todos_mod.CloseSignal(task="领取教材"),
                  [{"task": "参加春季摄影培训"}]) is None)
        check("通知与待办里的大小写差异不影响匹配",
              todos_mod.close_score("报名PDF排版培训已完成", "参加PDF排版培训")
              >= todos_mod.CLOSE_MATCH_MIN)
        check("候选里挑最像的那条",
              todos_mod.match_close(
                  todos_mod.CloseSignal(task="摄影培训取消"),
                  [{"task": "参加春季运动会"}, {"task": "参加春季摄影培训"}])[0]["task"]
              == "参加春季摄影培训")
        parsed = todos_mod._normalize(
            {"todos": [],
             "done": [{"task": "报名秋季联赛", "evidence": "秋季联赛报名已完成"}],
             "cancelled": [{"task": "摄影培训", "evidence": "摄影培训取消"}]},
            cfg, raw="", model="m", source_text="秋季联赛报名已完成；摄影培训取消。")
        check("done / cancelled 解析成关闭指令，不混进待办",
              len(parsed.entries) == 0 and len(parsed.closes) == 2
              and {c.state for c in parsed.closes} == {"done", "cancelled"},
              [(c.state, c.task) for c in parsed.closes])
        scanned = todos_mod.scan_closes("摄影培训已取消。秋季联赛报名已完成。别忘了带证件。")
        check("代码自己也能扫出状态通知（不依赖模型的 done/cancelled 两栏）",
              {(c.state, c.task) for c in scanned}
              == {("cancelled", "摄影培训"), ("done", "秋季联赛报名")},
              [(c.state, c.task) for c in scanned])
        check("没有状态词的句子不会被误判成通知",
              todos_mod.scan_closes("摄影培训在11月5日举行。") == [])
        check("「恢复」类通知被认出来，且优先于「取消」",
              [(c.state, c.task) for c in todos_mod.scan_closes("摄影培训已恢复举办。")]
              == [("reopen", "摄影培训")]
              and todos_mod.scan_closes("先前取消的摄影培训现已恢复举办")[0].state == "reopen",
              [(c.state, c.task)
               for c in todos_mod.scan_closes("先前取消的摄影培训现已恢复举办")])
        tid = store.add_todo(item_id=999, source_name="x.txt", record_path="r",
                             task="参加春季摄影培训", start_date="2026-11-05",
                             end_date="2026-11-05", created_at="2026-11-06 10:00:00")
        store.close_todo(tid, state="cancelled", closed_by="通知A", ts="t1")
        check("取消后从 active 消失、进 cancelled",
              all(r["id"] != tid for r in store.active_todos())
              and any(r["id"] == tid for r in store.todos_in_state("cancelled")))
        store.reopen_todo(tid, by="通知B", ts="t2")
        back = [r for r in store.active_todos() if r["id"] == tid]
        check("恢复后回到 active，并记下是谁叫回来的",
              len(back) == 1 and (back[0]["reopened_by"] or "") == "通知B"
              and not [r for r in store.todos_in_state("cancelled") if r["id"] == tid])
        md_re = library._item_markdown(
            task="参加春季摄影培训", start="2026-11-05", end="2026-11-05", votes=6, passes=6,
            source_name="a.txt", record_path="r", record_rel="r", evidence="摄影培训",
            reopened_by="通知.txt（材料日期 2026-11-06）")
        check("恢复过的待办回到待办态，并留下 🔄 痕迹",
              "state:" not in md_re and "🔄 **已恢复**" in md_re, md_re[:160])
        store.close_todo(tid, state="cancelled", closed_by="通知B2", ts="t3")
        res_open = todos_mod.TodoResult(closes=[
            todos_mod.CloseSignal(task="春季摄影培训", state="reopen")])
        pipeline._apply_closes(store, res_open, item_id=1000, source_name="通知C.txt",
                               doc_date="2026-11-08", verbose=False)
        after = [r for r in store.active_todos() if r["id"] == tid]
        check("「恢复」通知走完整归档链路也会生效（pipeline 那条路）",
              len(after) == 1 and (after[0]["reopened_by"] or "").startswith("通知C.txt"),
              [(r["task"], r["reopened_by"] or "") for r in after])
        md_done = library._item_markdown(
            task="参加春季摄影培训", start="2026-11-05", end="2026-11-05", votes=6, passes=6,
            source_name="a.txt", record_path="r", record_rel="r", evidence="摄影培训",
            state="cancelled", closed_by="通知.txt（材料日期 2026-11-06）")
        check("已取消的事项文件带 ❌ 标注与 state 字段",
              "state: cancelled" in md_done and "❌ **已取消**" in md_done, md_done[:160])
        md_ok = library._item_markdown(
            task="参加春季摄影培训", start="2026-11-05", end="2026-11-05", votes=6, passes=6,
            source_name="a.txt", record_path="r", record_rel="r", evidence="摄影培训")
        check("未办结的事项文件不写状态字段、也没有状态标注",
              "state:" not in md_ok and "已完成" not in md_ok
              and "已取消" not in md_ok and "已恢复" not in md_ok)

        section("喂给模型的文件名要收短（长哈希名会让 4B 退化）")
        check("超长截图名收成「手机截图_日期」",
              todos_mod.friendly_source(
                  "Screenshot_2026-11-03-09-12-30_0123456789abcdef0123456789abcdef.jpg")
              == "手机截图_2026-11-03",
              todos_mod.friendly_source(
                  "Screenshot_2026-11-03-09-12-30_0123456789abcdef0123456789abcdef.jpg"))
        check("干净短名原样保留", todos_mod.friendly_source("会议纪要.txt") == "会议纪要")
        check("长数字串的图片名收成「图片」",
              todos_mod.friendly_source("Image_1700000000000_163.png") == "图片")
        check("毫秒时间戳的文本名收成「文本记录」",
              todos_mod.friendly_source("text-1700000000000.txt") == "文本记录")
        long_prompt = todos_mod.build_prompt(
            source_name="Screenshot_2026-11-03-09-12-30_0123456789abcdef0123456789abcdef.jpg",
            doc_date="2026-10-14", today="2026-10-14", body_text="10/14 讲座", max_chars=1000)
        check("提示词里不再出现长哈希名",
              "0123456789abcdef0123456789abcdef" not in long_prompt
              and "手机截图_2026-11-03" in long_prompt)

        section("提示词里的两条硬规则（防被误删）")
        prompt = todos_mod.build_prompt(source_name="a.txt", doc_date="2026-10-14",
                                        today="2026-10-14",
                                        body_text="秋季联赛将于10.31举行，欢迎报名。",
                                        max_chars=4000)
        check("写了「举行日=参加，不是报名」",
              "参加" in prompt and "不是「报名" in prompt, prompt[:0] or "")
        check("写了「没给报名时间就不要硬凑」", "不要" in prompt and "硬凑" in prompt)
        check("写了「不要替当事人挑选项」", "不要替当事人" in prompt)
        check("附了时间点核对清单", "材料里出现过的时间点" in prompt and "10.31" in prompt)

        res2 = todos_mod._normalize(
            {"todos": [{"task": "编个日期", "start": near, "end": near, "evidence": "e"}]},
            cfg, raw="", model="m", source_text="请及时完成相关工作，谢谢。")
        check("材料无时间词时，模型给的日期被丢掉",
              res2.dropped_no_anchor == 1 and not res2.entries,
              f"{res2.dropped_no_anchor} / {res2.entries}")
        res3 = todos_mod._normalize(
            {"todos": [{"task": "真的有日期", "start": near, "end": near, "evidence": "e"}]},
            cfg, raw="", model="m", source_text="请于 10 月 20 日前完成。")
        check("材料有时间词时，正常保留", len(res3.entries) == 1, str(res3.entries))

        # ---------------------------------------------------------- 整轮
        section("整轮流程（文本 + 图片 + 异物 + 重复）")
        store.close()
        shutil.rmtree(tmp, ignore_errors=True)
        raw.mkdir(parents=True, exist_ok=True)
        (tmp / "state").mkdir(parents=True, exist_ok=True)

        text_src = raw / "通知.txt"
        text_src.write_text("请在10月20日前提交材料。", encoding="utf-8")
        img_src = make_png(raw / "Screenshot_2027-04-05-16-30-00-42_abc.jpg", (1080, 2340))
        foreign_src = raw / "video.mp4"
        foreign_src.write_bytes(b"\x00" * 256)
        old = (_dt.datetime.now() - _dt.timedelta(minutes=5)).timestamp()
        for p in (text_src, img_src, foreign_src):
            os.utime(p, (old, old))

        sc = scan(cfg)
        check("扫到 3 个待处理", len(sc.ready) == 3, str([p.name for p in sc.ready]))
        check("识别出 1 个非文本/图片", len(sc.foreign) == 1,
              str([p.name for p in sc.foreign]))

        store = Store(cfg.db_path)
        # 模型都不可用 + on_model_error=defer → 一个都不该动
        # （preheat=False：自检不许真烧 CPU —— 虽然 OCR 不健康时本来也会跳过）
        s = pipeline.run(cfg, store, Options(verbose=False, preheat=False))
        check("模型不可用时文本/图片全部暂缓",
              s["deferred"] == 2 and s["filed"] == 0 and s["foreign"] == 1, str(s)[:200])
        check("暂缓后 raw 里文件还在", text_src.exists())
        check("暂缓记录入库", store.item_counts().get("deferred") == 2,
              str(store.item_counts()))

        s = pipeline.run(cfg, store, Options(no_llm=True, no_ocr=True, force=True,
                                             verbose=False, preheat=False))
        check("强制模式下 2 个入档案", s["filed"] == 2, str(s))
        check("异物按 error 留下", s["foreign"] == 1 and foreign_src.exists(), str(s))
        check("异物记了一条 foreign", store.item_counts().get(FOREIGN) == 1,
              str(store.item_counts()))
        check("文本原件已移走", not text_src.exists())
        check("文本落进 records",
              (records / "通知.txt").is_file(), str(list(records.iterdir())))
        bundle = records / "Screenshot_2027-04-05-16-30-00-42_abc"
        check("图片打包目录已建", bundle.is_dir(), str(bundle))
        check("打包里有原图", (bundle / img_src.name).is_file())
        check("打包里有 text.md", (bundle / "text.md").is_file())
        check("图片原件已移走", not img_src.exists())
        check("待办目录已重建（0 条也算跑过）", todos_dir.is_dir())

        # 重复内容
        text_src.write_text("请在10月20日前提交材料。", encoding="utf-8")
        os.utime(text_src, (old, old))
        s = pipeline.run(cfg, store, Options(no_llm=True, no_ocr=True, verbose=False,
                                             preheat=False))
        check("重复内容被识别", s["duplicate"] == 1, str(s))
        check("重复副本进 raw/_duplicate/",
              (raw / "_duplicate" / "通知.txt").is_file(),
              str(list((raw / "_duplicate").iterdir())))

        # ---------------------------------------------------------- 回退
        section("回退 undo（含打包目录）")
        row = store.conn.execute(
            "SELECT * FROM items WHERE record_kind='bundle' AND status='filed' LIMIT 1"
        ).fetchone()
        store.add_todo(item_id=row["id"], source_name=row["src_name"],
                       record_path=row["record_path"], task="该撤下的待办",
                       start_date="2026-10-20", end_date="2026-10-20",
                       evidence="", created_at="now")
        library.rebuild_todos(cfg, store)
        check("撤下前时间文件夹里指向它",
              "该撤下的待办" in (todos_dir / "2026.10.20" / "items.md").read_text("utf-8"))
        check("撤下前事项文件在",
              (todos_dir / "items" / "20261020-该撤下的待办.md").is_file())
        # 模拟 cli undo 的核心动作
        rec = Path(row["record_path"])
        back = cfg.paths.raw / row["src_name"]
        library.transfer(rec / row["src_name"], back, "move")
        (rec / "text.md").unlink()
        rec.rmdir()
        store.delete_todos_of_item(row["id"])
        store.update_item(row["id"], status="undone", record_path=None)
        library.rebuild_todos(cfg, store)
        check("原图回到 raw", back.is_file(), str(back))
        check("打包目录已删掉", not rec.exists())
        check("相关待办已撤下", not (todos_dir / "2026.10.20").exists())
        check("事项文件一并撤下",
              not (todos_dir / "items" / "20261020-该撤下的待办.md").exists())

        store.close()

        # ---------------------------------------------------------- 热启动
        section("热启动（注入假温度源与假负载，自检不烧 CPU）")
        from archiver import preheat as preheat_mod

        real_temp = preheat_mod.find_package_temp()
        check("真机能按 name/label 找到 CPU 包温（不写死 hwmon 编号）",
              real_temp is not None and 15.0 <= real_temp <= 110.0, f"读到 {real_temp}")

        class FakeProc:
            def __init__(self):
                self.terminated = False
                self.killed = False

            def terminate(self):
                self.terminated = True

            def wait(self, timeout=None):
                return 0

            def kill(self):
                self.killed = True

        def seq_temp(values):
            box = {"i": 0}

            def rt():
                i = box["i"]
                box["i"] = i + 1
                return values[min(i, len(values) - 1)]
            return rt

        def no_load(_n):
            raise AssertionError("这条通路不该起负载")

        spawned: list = []

        def one_load(_n):
            procs = [FakeProc()]
            spawned.append(procs)
            return procs

        quiet = (lambda *_: None)
        pc = Config()
        pc.preheat.enabled = True
        pc.preheat.max_seconds = 0.2
        pc.preheat.poll_seconds = 0.0
        pc.preheat.target_temp_c = 62.0      # 配合下面的假温度序列（50/55/63）
        # 这一组测的是门控逻辑本身，把 min_seconds 归零，好让"温度达标"能立即生效
        # （min_seconds 自己的行为在下面单测）。
        pc.preheat.min_seconds = 0.0

        r = preheat_mod.maybe_warm(pc, pixels=9_000_000, verbose=quiet,
                                   read_temp=lambda: 70.0, spawn_load=no_load)
        check("包温已达标 → 跳过且不起负载", (not r.ran) and "≥" in r.reason, r.line())

        r = preheat_mod.maybe_warm(pc, pixels=9_000_000, verbose=quiet,
                                   read_temp=lambda: None, spawn_load=no_load)
        check("读不到温度 → 跳过（不挡归档）", (not r.ran) and "读不到" in r.reason, r.line())

        r = preheat_mod.maybe_warm(pc, pixels=1000, verbose=quiet,
                                   read_temp=lambda: 30.0, spawn_load=no_load)
        check("图不够大 → 跳过", (not r.ran) and "不够大" in r.reason, r.line())

        off = Config()
        off.preheat.enabled = False
        r = preheat_mod.maybe_warm(off, pixels=9_000_000, verbose=quiet,
                                   read_temp=lambda: 30.0, spawn_load=no_load)
        check("preheat.enabled=false → 跳过", (not r.ran) and "enabled" in r.reason, r.line())

        spawned.clear()
        r = preheat_mod.maybe_warm(pc, pixels=9_000_000, verbose=quiet,
                                   read_temp=seq_temp([50.0, 55.0, 63.0]),
                                   spawn_load=one_load, sleep=lambda *_: None)
        procs = spawned[0] if spawned else []
        check("温度达标 → 收工并报告起止温度",
              r.ran and r.reason == "达标" and r.start_c == 50.0 and r.end_c == 63.0,
              r.line())
        check("达标后负载进程被收干净",
              bool(procs) and all(p.terminated for p in procs), str(procs))

        # min_seconds：风扇从 0 到 1400 rpm 要 24~42 秒，而 turbo 尖峰能在 1 秒内
        # 把包温读数顶到 62~63 °C —— 若允许温度提前收工，预热会在风扇起转前退出
        # （2026-10-15 就是这么误判"无收益"的）。这条测"达标了也得先跑够 min_seconds"。
        spawned.clear()
        pc2 = Config()
        pc2.preheat.enabled = True
        pc2.preheat.min_seconds = 0.20
        pc2.preheat.max_seconds = 0.35
        pc2.preheat.poll_seconds = 0.0
        pc2.preheat.target_temp_c = 64.0
        tick2 = {"v": 0.0}

        def slow_clock():
            tick2["v"] += 0.05
            return tick2["v"]

        r = preheat_mod.maybe_warm(pc2, pixels=9_000_000, verbose=quiet,
                                   read_temp=seq_temp([50.0, 70.0]),
                                   spawn_load=one_load, sleep=lambda *_: None,
                                   clock=slow_clock)
        check("已达标但没跑够 min_seconds → 不提前收工",
              r.ran and r.reason == "达标" and r.seconds >= 0.20,
              f"{r.line()} · seconds={r.seconds:.2f}")
        check("min_seconds 那轮负载也收干净",
              bool(spawned) and all(p.terminated for p in spawned[0]), str(spawned))

        spawned.clear()
        tick = {"v": 0.0}

        def fast_clock():
            tick["v"] += 0.02
            return tick["v"]

        r = preheat_mod.maybe_warm(pc, pixels=9_000_000, verbose=quiet,
                                   read_temp=lambda: 30.0, spawn_load=one_load,
                                   sleep=lambda *_: None, clock=fast_clock)
        procs = spawned[0] if spawned else []
        check("抬不到目标 → 上限到点就停（超时）",
              r.ran and r.reason == "超时" and r.seconds <= 1.0, r.line())
        check("超时路径也把负载收干净",
              bool(procs) and all(p.terminated for p in procs), str(procs))

        small_img = make_png(tmp / "小截图.png", (1080, 1363))     # 147 万 < 门槛
        big_img = make_png(tmp / "扫描页.png", (2481, 3507))       # 870 万 ≥ 门槛
        r = preheat_mod.warm_for(pc, [small_img], verbose=quiet,
                                 read_temp=lambda: 30.0, spawn_load=no_load)
        check("warm_for：只有小图 → 跳过", not r.ran, r.line())
        spawned.clear()
        r = preheat_mod.warm_for(pc, [small_img, big_img], verbose=quiet,
                                 read_temp=seq_temp([50.0, 63.0]), spawn_load=one_load,
                                 sleep=lambda *_: None)
        check("warm_for：有 870 万像素的大图 → 预热",
              r.ran and r.reason == "达标", r.line())
        check("warm_for 起的负载也收干净",
              all(p.terminated for p in (spawned[0] if spawned else [])), str(spawned))

        # run() 的接线：OCR 就绪 + 大图 → 调预热；Options(preheat=False) → 不调
        calls: list = []
        orig_warm = preheat_mod.warm_for
        orig_health = pipeline.services.health
        orig_ocr = pipeline.vision.run_ocr
        try:
            preheat_mod.warm_for = lambda cfg, paths, **kw: (
                calls.append(len(paths)) or preheat_mod.PreheatResult(True, "达标", 1.0,
                                                                     50.0, 62.0, 2))
            pipeline.services.health = lambda *a, **k: True
            pipeline.vision.run_ocr = lambda path, cfg: vision.OcrResult(
                text="假 OCR", engine="fake", elapsed=0.1)

            pipe = tmp / "preheat-run"
            praw = pipe / "raw"
            praw.mkdir(parents=True, exist_ok=True)
            (pipe / "state").mkdir(parents=True, exist_ok=True)
            pcfg = Config()
            pcfg.preheat.enabled = True      # 默认已关；这里测的是 run() 的接线
            pcfg.paths.root = pipe
            pcfg.paths.raw = praw
            pcfg.paths.records = pipe / "records"
            pcfg.paths.todos = pipe / "todos"
            pcfg.paths.state_dir = pipe / "state"
            pstore = Store(pcfg.db_path)
            try:
                img_a = make_png(praw / "扫描页A.png", (2481, 3507))
                os.utime(img_a, (old, old))
                pipeline.run(pcfg, pstore, Options(no_llm=True, verbose=False))
                check("OCR 就绪 + 大图 → run() 调了热启动", len(calls) == 1, str(calls))
                calls.clear()
                img_b = make_png(praw / "扫描页B.png", (2481, 3506))
                os.utime(img_b, (old, old))
                pipeline.run(pcfg, pstore, Options(no_llm=True, verbose=False,
                                                  preheat=False))
                check("Options(preheat=False) → 不调热启动", len(calls) == 0, str(calls))
            finally:
                pstore.close()
        finally:
            preheat_mod.warm_for = orig_warm
            pipeline.services.health = orig_health
            pipeline.vision.run_ocr = orig_ocr

        # ---------------------------------------------------------- dry-run
        section("CLI 接线（防「加了命令行开关、忘了给 dataclass 加字段」）")
        # 真实教训：给 `inbox run` 加了 --fast，cli 里传了 fast=…，
        # 但 Options 少了这个字段 → 所有 `inbox run` 直接 TypeError，
        # 而单元测试都直接构造 Options，根本没走 CLI，所以当时全绿。
        import subprocess
        cli_cfg = tmp / "cli-config.toml"
        cli_cfg.write_text(f"""
[paths]
root = "{tmp}/cliarchive"
raw = "{tmp}/cliarchive/raw"
records = "{tmp}/cliarchive/records"
todos = "{tmp}/cliarchive/todos"
state_dir = "{tmp}/clistate"

[models]
autostart = false
llm_port = 18080
ocr_port = 18083

[behavior]
trigger_delay_seconds = 0
""", encoding="utf-8")
        (tmp / "cliarchive" / "raw").mkdir(parents=True, exist_ok=True)
        (tmp / "cliarchive" / "raw" / "一件.txt").write_text("10 月 20 日前交材料。",
                                                           encoding="utf-8")
        old_t = (_dt.datetime.now() - _dt.timedelta(minutes=5)).timestamp()
        os.utime(tmp / "cliarchive" / "raw" / "一件.txt", (old_t, old_t))

        def run_cli(*argv):
            return subprocess.run(
                [sys.executable, str(PROJECT / "bin" / "inbox"),
                 "--config", str(cli_cfg), *argv],
                capture_output=True, text=True, timeout=120)

        r = run_cli("run", "--dry-run")
        check("inbox run --dry-run 走得通（走的是真 CLI）", r.returncode == 0,
              r.stderr[-300:])
        r = run_cli("run", "--no-llm", "--no-ocr", "--force", "-q")
        check("inbox run 的开关组合走得通", r.returncode == 0, r.stderr[-300:])
        check("CLI 真的把文件归了档",
              (tmp / "cliarchive" / "records" / "一件.txt").is_file())
        r = run_cli("run", "--fast", "--limit", "1", "--only", "没有这个", "-q")
        check("inbox run --fast 等开关解析正常", r.returncode == 0, r.stderr[-300:])
        r = run_cli("run", "--no-preheat", "--no-llm", "--no-ocr", "--force", "-q")
        check("inbox run --no-preheat 走得通（真 CLI，验 Options 接线）",
              r.returncode == 0, r.stderr[-300:])
        r = run_cli("reextract", "--all", "--dry-run", "--fast", "-q")
        check("inbox reextract --fast 走得通", r.returncode == 0, r.stderr[-300:])
        r = run_cli("todos", "--last", "7")
        check("inbox todos --last 走得通", r.returncode == 0, r.stderr[-300:])
        r = run_cli("status")
        check("inbox status 走得通", r.returncode == 0, r.stderr[-300:])

        section("dry-run 不动文件")
        store = Store(cfg.db_path)
        plan_src = raw / "计划用.txt"
        plan_src.write_text("hello", encoding="utf-8")
        os.utime(plan_src, (old, old))
        before = sorted(p.name for p in raw.rglob("*"))
        s = pipeline.run(cfg, store, Options(dry_run=True, no_llm=True, no_ocr=True,
                                             verbose=False, preheat=False))
        after = sorted(p.name for p in raw.rglob("*"))
        check("dry-run 不改 raw", before == after, f"{before} vs {after}")
        check("dry-run 不留 filed 记录", s["filed"] == 0)
        store.close()

    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"\n{'=' * 46}\n通过 {PASS} · 失败 {FAIL}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
