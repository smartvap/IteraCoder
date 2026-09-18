# -*- coding: utf-8 -*-
"""知识页服务：页面 CRUD + 向量化入库 + 增量同步

核心流程（写入/更新）：
  原始文本 → parser 解析（按扩展名，可扩展格式）
          → 分块 → embedding 向量化 → ChromaDB upsert
          → 台账 upsert + chunk 映射（content_hash 记录）

增量同步 sync：扫描磁盘文件 ↔ 台账 hash diff
  → 相同跳过 / 不同更新 / 新增插入 / 消失删除
"""
from __future__ import annotations

import logging
import os

from ..utils.chunker import split_text
from ..utils.hashing import sha256
from ..utils.path_utils import safe_join
from .meta_store import MetaStore
from .parsers import get_parser, supported_extensions
from .parsers.markdown_parser import MarkdownParser
from .space_service import SpaceService
from .vector_store import VectorStore

logger = logging.getLogger(__name__)


class PageError(ValueError):
    """业务错误（含 HTTP 语义，code=404/409/503 时由路由层映射）"""

    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.code = code


class PageService:
    def __init__(self, settings, meta_store: MetaStore, vector_store: VectorStore,
                 space_service: SpaceService, embedding_client):
        self.s = settings
        self.meta = meta_store
        self.vectors = vector_store
        self.spaces = space_service
        self.embedding = embedding_client

    # ---------- 路径 ----------
    def _page_file(self, space: str, path: str) -> str:
        """安全解析 页面文件绝对路径（防穿越）"""
        if not self.meta.get_space(space):
            raise PageError(f"空间不存在: {space}", 404)
        base = os.path.join(self.s.wiki_dir, space)
        return str(safe_join(base, path))

    def _raw_dir(self, space: str) -> str:
        return os.path.join(self.s.raw_dir, space)

    # ---------- 索引（解析→分块→向量化→台账） ----------
    def _index_raw(self, space: str, path: str, raw: str) -> dict:
        """对一个页面的完整文本执行索引，返回 {hash, chunk_count}"""
        parser = get_parser(path)
        if parser is None:
            ext = os.path.splitext(path)[1].lower()
            raise PageError(f"不支持的文件格式: {ext or '(无扩展名)'}", 422)
        doc = parser.parse_text(raw, file_name=os.path.basename(path))

        content_hash = sha256(raw)
        chunks = split_text(doc.content, self.s.chunk_size, self.s.chunk_overlap)
        page_type = str(doc.metadata.get("type") or "doc")
        tags = doc.metadata.get("tags") or []
        tags_joined = ",".join(str(t) for t in tags)

        if chunks:
            chunk_texts = [c.text for c in chunks]
            try:
                embeddings = self.embedding.embed_texts(chunk_texts)
            except Exception as e:  # noqa: BLE001
                logger.error("embedding 失败(space=%s path=%s): %s", space, path, e)
                raise PageError(f"Embedding 服务不可用: {e}", 503) from e

            ids = [f"{path}::{c.index}" for c in chunks]
            metadatas = [{
                "path": path, "title": doc.title, "type": page_type,
                "tags": tags_joined, "chunk_index": c.index,
            } for c in chunks]
            self.vectors.upsert_chunks(space, ids, chunk_texts, metadatas, embeddings)
        else:
            # 空正文：清空该页向量（若存在旧向量）
            self.vectors.delete_by_page(space, path)

        # 台账
        chunk_ids = [f"{path}::{c.index}" for c in chunks]
        chunk_hashes = [sha256(c.text) for c in chunks]
        chunk_indexes = [c.index for c in chunks]
        self.meta.replace_chunks(space, path, chunk_ids, chunk_hashes, chunk_indexes)
        self.meta.upsert_page(
            space, path, title=doc.title, page_type=page_type, tags=tags,
            content_hash=content_hash, status="active", chunk_count=len(chunks),
        )
        return {"content_hash": content_hash, "chunk_count": len(chunks), "path": path}

    # ---------- CRUD ----------
    def create(self, space: str, path: str, content: str) -> dict:
        path = self._normalize_path(path)
        file_path = self._page_file(space, path)
        if os.path.exists(file_path):
            raise PageError(f"页面已存在: {path}", 409)
        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(content)
        try:
            return self._index_raw(space, path, content)
        except Exception:
            # 索引失败时回滚文件，避免半状态
            if os.path.exists(file_path):
                os.remove(file_path)
            raise

    def get(self, space: str, path: str, include_content: bool = True) -> dict | None:
        path = self._normalize_path(path)
        file_path = self._page_file(space, path)
        if not os.path.exists(file_path):
            raise PageError(f"页面不存在: {path}", 404)
        meta_row = self.meta.get_page(space, path) or {}
        with open(file_path, "r", encoding="utf-8") as f:
            raw = f.read()
        out = {
            "path": path,
            "title": meta_row.get("title", ""),
            "type": meta_row.get("type", "doc"),
            "tags": meta_row.get("tags", []),
            "content_hash": meta_row.get("content_hash", ""),
            "status": meta_row.get("status", "active"),
            "chunk_count": meta_row.get("chunk_count", 0),
            "updated_at": meta_row.get("updated_at", ""),
        }
        if include_content:
            out["content"] = raw
        return out

    def update(self, space: str, path: str, content: str) -> dict:
        path = self._normalize_path(path)
        file_path = self._page_file(space, path)
        if not os.path.exists(file_path):
            raise PageError(f"页面不存在: {path}", 404)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(content)
        return self._index_raw(space, path, content)

    # ---------- 多格式上传（归一化转 md） ----------
    def import_document(self, space: str, file_name: str, data: bytes,
                        subdir: str = "", overwrite: bool = False) -> dict:
        """上传任意支持格式 → 归一化为 md 页面入库。

        支持：.md/.markdown（原样保留 frontmatter）以及 .txt/.docx/.pdf/
        .pptx/.xlsx（解析 → MarkdownParser 序列化生成带 frontmatter 的 md）。

        返回 {"path", "content_hash", "chunk_count", "created"}。
        """
        file_name = os.path.basename((file_name or "").replace("\\", "/"))
        if not file_name:
            raise PageError("文件名为空", 422)
        ext = os.path.splitext(file_name)[1].lower()

        parser = get_parser(file_name)
        if parser is None:
            raise PageError(
                f"不支持的格式: {ext or '(无扩展名)'}（支持 {', '.join(sorted(self._supported()))}）",
                422,
            )

        # 归一化为 md 文本：md/markdown 原样保留，其余解析后序列化
        if ext in (".md", ".markdown"):
            md_text = self._decode_utf8(data, file_name)
            target_name = file_name  # 保留原名（含 .md 扩展名）
        else:
            if ext == ".txt":
                raw_text = self._decode_utf8(data, file_name)
                doc = parser.parse_text(raw_text, file_name=file_name)
            else:
                doc = parser.parse_bytes(data, file_name=file_name)
            md_text = MarkdownParser().serialize(doc)
            target_name = os.path.splitext(file_name)[0] + ".md"

        subdir = (subdir or "").replace("\\", "/").strip("/")
        path = self._normalize_path(
            f"{subdir}/{target_name}" if subdir else target_name
        )
        file_path = self._page_file(space, path)

        if os.path.exists(file_path):
            if not overwrite:
                raise PageError(f"页面已存在: {path}", 409)
            # 覆盖 → 走更新逻辑（写文件 + 重新索引）
            with open(file_path, "w", encoding="utf-8") as f:
                f.write(md_text)
            idx = self._index_raw(space, path, md_text)
            idx["created"] = False
            return idx

        # 新建：写文件 + 索引，失败回滚避免半状态
        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(md_text)
        try:
            idx = self._index_raw(space, path, md_text)
        except Exception:
            if os.path.exists(file_path):
                os.remove(file_path)
            raise
        idx["created"] = True
        return idx

    @staticmethod
    def _decode_utf8(data: bytes, file_name: str) -> str:
        """UTF-8 解码（容忍 BOM）；失败给出友好报错"""
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as e:
            raise PageError(
                f"无法读取 {file_name}: 请确保文件为 UTF-8 编码", 422
            ) from e
        return text[1:] if text.startswith("\ufeff") else text

    @staticmethod
    def _supported() -> list[str]:
        return supported_extensions()

    def delete(self, space: str, path: str) -> None:
        path = self._normalize_path(path)
        file_path = self._page_file(space, path)
        if not os.path.exists(file_path):
            raise PageError(f"页面不存在: {path}", 404)
        os.remove(file_path)
        self.vectors.delete_by_page(space, path)
        self.meta.delete_page(space, path)

    def list(self, space: str, page_type: str | None = None) -> list[dict]:
        if not self.meta.get_space(space):
            raise PageError(f"空间不存在: {space}", 404)
        rows = self.meta.list_pages(space, page_type=page_type)
        for r in rows:
            r.pop("content_hash", None)
        return rows

    # ---------- 增量同步 ----------
    def sync(self, space: str) -> dict:
        if not self.meta.get_space(space):
            raise PageError(f"空间不存在: {space}", 404)
        base = os.path.join(self.s.wiki_dir, space)
        os.makedirs(base, exist_ok=True)

        # 1. 扫描磁盘文件（parser 支持的格式）
        disk: dict[str, str] = {}  # path -> raw
        skipped_ext: set[str] = set()
        for root, _dirs, files in os.walk(base):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, base).replace("\\", "/")
                if get_parser(rel) is None:
                    skipped_ext.add(os.path.splitext(fn)[1].lower())
                    continue
                with open(full, "r", encoding="utf-8") as f:
                    disk[rel] = f.read()

        # 2. 台账记录
        meta_pages = {r["path"]: r for r in self.meta.list_pages(space)}

        added: list[str] = []
        updated: list[str] = []
        deleted: list[str] = []
        errors: list[str] = []
        skipped = 0

        for path, raw in disk.items():
            disk_hash = sha256(raw)
            row = meta_pages.get(path)
            if row and row.get("content_hash") == disk_hash:
                skipped += 1
                continue
            try:
                self._index_raw(space, path, raw)
                (added if row is None else updated).append(path)
            except Exception as e:  # noqa: BLE001
                logger.exception("sync 索引失败: %s", path)
                errors.append(f"{path}: {e}")

        for path in meta_pages:
            if path not in disk:
                try:
                    self.vectors.delete_by_page(space, path)
                    self.meta.delete_page(space, path)
                    deleted.append(path)
                except Exception as e:  # noqa: BLE001
                    logger.exception("sync 删除失败: %s", path)
                    errors.append(f"{path}: {e}")

        return {
            "space": space, "added": added, "updated": updated, "deleted": deleted,
            "skipped": skipped, "errors": errors,
            "skipped_extensions": sorted(skipped_ext),
        }

    # ---------- 工具 ----------
    @staticmethod
    def _normalize_path(path: str) -> str:
        path = (path or "").strip().replace("\\", "/")
        path = path.lstrip("/")
        if not path:
            raise PageError("path 不能为空", 422)
        return path
