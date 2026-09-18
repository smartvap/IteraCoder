# -*- coding: utf-8 -*-
"""SQLite 台账：页面注册表与 chunk 映射（增量同步的基石）

职责：
- 记录知识空间、页面元数据（path/title/type/tags/content_hash/status）
- 记录页面 chunk 映射（id = {path}::{chunk_index}）
- 通过 content_hash diff 判定"哪些页面变了"，支撑增量同步

实现：每次操作短连接 + WAL 模式（简单、线程安全）。
"""
from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager

from ..config import Settings
from ..utils.path_utils import safe_join

_SCHEMA = """
CREATE TABLE IF NOT EXISTS spaces (
  name            TEXT PRIMARY KEY,
  types           TEXT NOT NULL DEFAULT '[]',   -- JSON 数组
  collection_name TEXT,
  created_at      TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS pages (
  space         TEXT NOT NULL,
  path          TEXT NOT NULL,
  title         TEXT NOT NULL DEFAULT '',
  type          TEXT NOT NULL DEFAULT 'doc',
  tags          TEXT NOT NULL DEFAULT '[]',     -- JSON 数组
  content_hash  TEXT NOT NULL DEFAULT '',
  status        TEXT NOT NULL DEFAULT 'active', -- active|deprecated|draft
  chunk_count   INTEGER NOT NULL DEFAULT 0,
  updated_at    TEXT DEFAULT (datetime('now')),
  PRIMARY KEY (space, path)
);
CREATE TABLE IF NOT EXISTS page_chunks (
  id           TEXT PRIMARY KEY,               -- {path}::{chunk_index}
  space        TEXT NOT NULL,
  page_path    TEXT NOT NULL,
  chunk_index  INTEGER NOT NULL,
  content_hash TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_chunks_page ON page_chunks (space, page_path);
CREATE INDEX IF NOT EXISTS idx_pages_space ON pages (space);
"""


class MetaStore:
    """SQLite 台账访问层。每个实例持有一个 db_path。"""

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._lock = threading.Lock()
        self._init_db()

    # ---------- 连接管理 ----------
    @contextmanager
    def _conn(self):
        conn = sqlite3.connect(self.db_path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def _init_db(self) -> None:
        with self._lock, self._conn() as conn:
            conn.executescript(_SCHEMA)

    # ---------- 工具 ----------
    @staticmethod
    def _dumps(value) -> str:
        return json.dumps(value, ensure_ascii=False)

    @staticmethod
    def _loads(raw: str | None, default=None):
        if not raw:
            return default if default is not None else []
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return default if default is not None else []

    # ---------- 空间 ----------
    def create_space(self, name: str, types: list[str], collection_name: str) -> None:
        with self._lock, self._conn() as conn:
            conn.execute(
                "INSERT OR REPLACE INTO spaces (name, types, collection_name) VALUES (?,?,?)",
                (name, self._dumps(types), collection_name),
            )

    def get_space(self, name: str) -> dict | None:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM spaces WHERE name=?", (name,)).fetchone()
        if not row:
            return None
        d = dict(row)
        d["types"] = self._loads(d.get("types"))
        d["page_count"] = self.count_pages(name)
        return d

    def list_spaces(self) -> list[dict]:
        with self._conn() as conn:
            rows = conn.execute("SELECT * FROM spaces ORDER BY name").fetchall()
        out = []
        for row in rows:
            d = dict(row)
            d["types"] = self._loads(d.get("types"))
            d["page_count"] = self.count_pages(d["name"])
            out.append(d)
        return out

    def delete_space(self, name: str) -> None:
        with self._lock, self._conn() as conn:
            conn.execute("DELETE FROM page_chunks WHERE space=?", (name,))
            conn.execute("DELETE FROM pages WHERE space=?", (name,))
            conn.execute("DELETE FROM spaces WHERE name=?", (name,))

    # ---------- 页面 ----------
    def upsert_page(self, space: str, path: str, *, title: str = "", page_type: str = "doc",
                    tags: list[str] | None = None, content_hash: str = "",
                    status: str = "active", chunk_count: int = 0) -> None:
        with self._lock, self._conn() as conn:
            conn.execute(
                """
                INSERT INTO pages (space, path, title, type, tags, content_hash, status, chunk_count, updated_at)
                VALUES (?,?,?,?,?,?,?,?, datetime('now'))
                ON CONFLICT(space, path) DO UPDATE SET
                  title=excluded.title, type=excluded.type, tags=excluded.tags,
                  content_hash=excluded.content_hash, status=excluded.status,
                  chunk_count=excluded.chunk_count, updated_at=datetime('now')
                """,
                (space, path, title, page_type, self._dumps(tags or []),
                 content_hash, status, chunk_count),
            )

    def get_page(self, space: str, path: str) -> dict | None:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT * FROM pages WHERE space=? AND path=?", (space, path)
            ).fetchone()
        if not row:
            return None
        d = dict(row)
        d["tags"] = self._loads(d.get("tags"))
        return d

    def list_pages(self, space: str, page_type: str | None = None,
                   status: str | None = None) -> list[dict]:
        sql = "SELECT * FROM pages WHERE space=?"
        params: list = [space]
        if page_type:
            sql += " AND type=?"
            params.append(page_type)
        if status:
            sql += " AND status=?"
            params.append(status)
        sql += " ORDER BY path"
        with self._conn() as conn:
            rows = conn.execute(sql, params).fetchall()
        out = []
        for row in rows:
            d = dict(row)
            d["tags"] = self._loads(d.get("tags"))
            out.append(d)
        return out

    def delete_page(self, space: str, path: str) -> None:
        with self._lock, self._conn() as conn:
            conn.execute("DELETE FROM pages WHERE space=? AND path=?", (space, path))
            conn.execute("DELETE FROM page_chunks WHERE space=? AND page_path=?",
                         (space, path))

    def count_pages(self, space: str) -> int:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT COUNT(*) AS c FROM pages WHERE space=?", (space,)
            ).fetchone()
        return int(row["c"]) if row else 0

    # ---------- chunk ----------
    def replace_chunks(self, space: str, page_path: str, chunk_ids: list[str],
                       chunk_hashes: list[str], chunk_indexes: list[int]) -> None:
        """重建某页面的 chunk 映射（先删后插）"""
        with self._lock, self._conn() as conn:
            conn.execute("DELETE FROM page_chunks WHERE space=? AND page_path=?",
                         (space, page_path))
            for cid, ch, idx in zip(chunk_ids, chunk_hashes, chunk_indexes):
                conn.execute(
                    "INSERT OR REPLACE INTO page_chunks (id, space, page_path, chunk_index, content_hash)"
                    " VALUES (?,?,?,?,?)",
                    (cid, space, page_path, idx, ch),
                )

    def list_chunk_ids(self, space: str, page_path: str) -> list[str]:
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT id FROM page_chunks WHERE space=? AND page_path=?",
                (space, page_path),
            ).fetchall()
        return [r["id"] for r in rows]

    def list_all_page_paths(self, space: str) -> list[str]:
        with self._conn() as conn:
            rows = conn.execute("SELECT path FROM pages WHERE space=?", (space,)).fetchall()
        return [r["path"] for r in rows]
