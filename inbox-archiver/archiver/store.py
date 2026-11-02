"""状态库（sqlite）：记住每个原文件被怎么处理过、抽出了哪些待办，保证可重跑、可回退。

todos/年.月.日/ 里的文件是**派生品**，随时可以用 `inbox todos --rebuild` 从这张表重建。
"""
from __future__ import annotations

import datetime as _dt
import json
import sqlite3
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    src_name       TEXT NOT NULL,       -- raw/ 里的原始文件名
    src_path       TEXT NOT NULL,
    size           INTEGER NOT NULL,
    mtime          REAL    NOT NULL,
    sha256         TEXT,
    kind           TEXT,                -- text | image | foreign
    doc_date       TEXT,                -- 归档依据的日期（EXIF / 文件名 / mtime）
    status         TEXT NOT NULL,       -- filed | foreign | duplicate | failed | deferred
    record_path    TEXT,                -- 落到 records/ 的路径（图片是打包目录）
    record_kind    TEXT,                -- file（文本原样） | bundle（图片打包目录）
    text_path      TEXT,                -- 打包里的 text.md
    ocr_engine     TEXT,
    ocr_chars      INTEGER,
    todo_count     INTEGER DEFAULT 0,
    error          TEXT,
    created_at     TEXT,
    updated_at     TEXT
);
CREATE INDEX IF NOT EXISTS idx_items_sha    ON items(sha256);
CREATE INDEX IF NOT EXISTS idx_items_status ON items(status);

CREATE TABLE IF NOT EXISTS todos (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id     INTEGER,
    source_name TEXT,                   -- 来自哪个原文件
    record_path TEXT,                   -- 来自 records/ 里的哪个东西
    task        TEXT NOT NULL,          -- 待办本身
    start_date  TEXT NOT NULL,          -- YYYY-MM-DD（必填，没日期的不入库）
    end_date    TEXT NOT NULL,          -- 同日 = 单日事项
    evidence    TEXT,                   -- 原文里对应的那句话
    votes       INTEGER DEFAULT 1,      -- 多路抽取里有几路抽到了它（一致度）
    passes      INTEGER DEFAULT 1,      -- 这一轮一共跑了几路
    variants    TEXT,                   -- 多路说法不一致时留下的其它说法（JSON 数组）
    evidence_ok INTEGER DEFAULT 1,      -- 确定性核对：依据那句话在材料里找得到吗
    date_ok     INTEGER DEFAULT 1,      -- 确定性核对：这个日期字面在材料里出现过吗
    redated     TEXT,                   -- 日期由依据里的相对说法换算过时记这条：旧→新（依据「本周日」换算）
    state       TEXT DEFAULT 'active',  -- active=要做；done=已完成；cancelled=已取消
    closed_by   TEXT,                   -- 哪条材料通知的（来源名 + 材料日期）
    closed_at   TEXT,
    reopened_by TEXT,                   -- 后来又被「恢复 / 重新举办」的通知叫回来时记这条
    reopened_at TEXT,
    created_at  TEXT
);
CREATE INDEX IF NOT EXISTS idx_todos_start ON todos(start_date);
CREATE INDEX IF NOT EXISTS idx_todos_end   ON todos(end_date);
CREATE INDEX IF NOT EXISTS idx_todos_item  ON todos(item_id);

CREATE TABLE IF NOT EXISTS logs (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      TEXT,
    level   TEXT,
    message TEXT
);
"""

# items.status
FILED = "filed"
FOREIGN = "foreign"
DUPLICATE = "duplicate"
FAILED = "failed"
DEFERRED = "deferred"


class Store:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.path))
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        self._migrate()
        self.conn.commit()

    def _migrate(self) -> None:
        """给老库补上后加的列（不重建表，用户已有的记录不会丢）。"""
        wanted = {
            "todos": {
                "votes": "INTEGER DEFAULT 1",
                "passes": "INTEGER DEFAULT 1",
                "variants": "TEXT",
                "evidence_ok": "INTEGER DEFAULT 1",
                "date_ok": "INTEGER DEFAULT 1",
                "redated": "TEXT",
                "state": "TEXT DEFAULT 'active'",
                "closed_by": "TEXT",
                "closed_at": "TEXT",
                "reopened_by": "TEXT",
                "reopened_at": "TEXT",
            },
        }
        for table, cols in wanted.items():
            have = {r["name"] for r in
                    self.conn.execute(f"PRAGMA table_info({table})").fetchall()}
            for name, decl in cols.items():
                if name not in have:
                    self.conn.execute(f"ALTER TABLE {table} ADD COLUMN {name} {decl}")

    def close(self) -> None:
        try:
            self.conn.close()
        except Exception:  # noqa: BLE001
            pass

    # ---------------------------------------------------------------- 写

    def _columns(self, table: str) -> set[str]:
        cache = getattr(self, "_cols", None)
        if cache is None:
            cache = self._cols = {}
        if table not in cache:
            rows = self.conn.execute(f"PRAGMA table_info({table})").fetchall()
            cache[table] = {r["name"] for r in rows}
        return cache[table]

    def add_item(self, **fields: Any) -> int:
        cols = [c for c in fields if c in self._columns("items")]
        sql = f"INSERT INTO items ({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))})"
        cur = self.conn.execute(sql, [fields[c] for c in cols])
        self.conn.commit()
        return int(cur.lastrowid)

    def update_item(self, item_id: int, **fields: Any) -> None:
        cols = [c for c in fields if c in self._columns("items")]
        if not cols:
            return
        sets = ", ".join(f"{c}=?" for c in cols)
        self.conn.execute(f"UPDATE items SET {sets} WHERE id=?",
                          [fields[c] for c in cols] + [item_id])
        self.conn.commit()

    def add_todo(self, **fields: Any) -> int:
        cols = [c for c in fields if c in self._columns("todos")]
        sql = f"INSERT INTO todos ({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))})"
        cur = self.conn.execute(sql, [fields[c] for c in cols])
        self.conn.commit()
        return int(cur.lastrowid)

    def close_todo(self, todo_id: int, *, state: str, closed_by: str, ts: str) -> None:
        """把一条待办标成已完成/已取消 **不删除** —— 事项文件留在 todos/ 里并带状态标注。"""
        self.conn.execute(
            "UPDATE todos SET state=?, closed_by=?, closed_at=? WHERE id=?",
            (state, closed_by, ts, todo_id))
        self.conn.commit()

    def todos_in_state(self, state: str) -> list[sqlite3.Row]:
        """某个状态下的待办（active=要做的；cancelled=已取消，恢复时从这里找）。"""
        return list(self.conn.execute(
            "SELECT * FROM todos WHERE COALESCE(state, 'active') = ? "
            "ORDER BY start_date, task", (state,)))

    def active_todos(self) -> list[sqlite3.Row]:
        """还没办结的待办（通知类材料要往这批里匹配）。"""
        return self.todos_in_state("active")

    def reopen_todo(self, todo_id: int, *, by: str, ts: str) -> None:
        """把已取消（或已完成）的待办改回 active —— 通知说「恢复 / 重新举办」时用。"""
        self.conn.execute(
            "UPDATE todos SET state='active', reopened_by=?, reopened_at=? WHERE id=?",
            (by, ts, todo_id))
        self.conn.commit()

    def log(self, level: str, message: str, ts: str) -> None:
        self.conn.execute("INSERT INTO logs (ts, level, message) VALUES (?,?,?)",
                          (ts, level, message))
        self.conn.commit()

    def forget_source(self, src_path: str) -> None:
        """清掉某个原文件留下的「非归档」记录（暂缓/失败/异物），免得重复累积。"""
        self.conn.execute(
            "DELETE FROM items WHERE src_path=? AND status IN (?,?,?)",
            (src_path, DEFERRED, FAILED, FOREIGN))
        self.conn.commit()

    # ---------------------------------------------------------------- 读

    def by_sha(self, sha: str) -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT * FROM items WHERE sha256=? AND status IN (?,?) ORDER BY id LIMIT 1",
            (sha, FILED, DUPLICATE)).fetchone()

    def get_item(self, item_id: int) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM items WHERE id=?", (item_id,)).fetchone()

    def recent_items(self, limit: int = 20, status: str | None = None) -> list[sqlite3.Row]:
        if status:
            return self.conn.execute(
                "SELECT * FROM items WHERE status=? ORDER BY id DESC LIMIT ?",
                (status, limit)).fetchall()
        return self.conn.execute(
            "SELECT * FROM items ORDER BY id DESC LIMIT ?", (limit,)).fetchall()

    def item_counts(self) -> dict[str, int]:
        rows = self.conn.execute(
            "SELECT status, COUNT(*) AS n FROM items GROUP BY status").fetchall()
        return {r["status"]: r["n"] for r in rows}

    def pending(self) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM items WHERE status IN (?,?) ORDER BY id",
            (DEFERRED, FAILED)).fetchall()

    def all_todos(self) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM todos ORDER BY start_date, end_date, id").fetchall()

    def todos_in_range(self, start: str, end: str) -> list[sqlite3.Row]:
        """区间 [start, end] 内**任意一天有效**的待办（跨日事项只要与区间有交集就算）。"""
        return self.conn.execute(
            "SELECT * FROM todos WHERE start_date <= ? AND end_date >= ? "
            "ORDER BY start_date, end_date, id", (end, start)).fetchall()

    def todos_for_day(self, day: str) -> list[sqlite3.Row]:
        return self.todos_in_range(day, day)

    def todos_of_item(self, item_id: int) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM todos WHERE item_id=? ORDER BY start_date", (item_id,)).fetchall()

    def delete_todos_of_item(self, item_id: int) -> None:
        self.conn.execute("DELETE FROM todos WHERE item_id=?", (item_id,))
        self.conn.commit()

    def todo_days(self) -> list[str]:
        """所有出现过待办的日子（用于重建目录）。"""
        days: set[str] = set()
        for r in self.conn.execute("SELECT start_date, end_date FROM todos"):
            try:
                d0 = _dt.date.fromisoformat(r["start_date"])
                d1 = _dt.date.fromisoformat(r["end_date"])
            except (TypeError, ValueError):
                continue
            cur = d0
            while cur <= d1:
                days.add(cur.isoformat())
                cur += _dt.timedelta(days=1)
        return sorted(days)


def tags_to_json(tags: Iterable[str] | str | None) -> str:
    if tags is None:
        return "[]"
    if isinstance(tags, str):
        return tags
    return json.dumps([str(t) for t in tags], ensure_ascii=False)
