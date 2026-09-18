# -*- coding: utf-8 -*-
"""ChromaDB 向量存储封装

- 每知识空间一个 collection（name = space.name），自动持久化到 data/chroma
- 记录 id 规则："{path}::{chunk_index}"
- metadata 携带 path/title/type/tags，支撑过滤与按页删除
- 显式传入 embedding（由 EmbeddingClient 计算），collection 不绑定 embedding_function
"""
from __future__ import annotations

import logging

import chromadb
from chromadb.api.models.Collection import Collection

logger = logging.getLogger(__name__)


class VectorStore:
    def __init__(self, persist_dir: str):
        self._client = chromadb.PersistentClient(path=persist_dir)
        self._collections: dict[str, Collection] = {}

    # ---------- collection ----------
    def _collection(self, space: str) -> Collection:
        col = self._collections.get(space)
        if col is None:
            col = self._client.get_or_create_collection(
                name=space, metadata={"hnsw:space": "cosine"}
            )
            self._collections[space] = col
        return col

    def create_space(self, space: str) -> None:
        self._collection(space)

    def delete_space(self, space: str) -> None:
        try:
            self._client.delete_collection(space)
        except Exception:  # noqa: BLE001  (collection 不存在时忽略)
            logger.debug("delete collection %s: not exists or failed", space)
        self._collections.pop(space, None)

    def count(self, space: str) -> int:
        try:
            return self._collection(space).count()
        except Exception:  # noqa: BLE001
            return 0

    # ---------- 写入 ----------
    def upsert_chunks(self, space: str, ids: list[str], documents: list[str],
                      metadatas: list[dict], embeddings: list[list[float]]) -> None:
        if not ids:
            return
        self._collection(space).upsert(
            ids=ids, documents=documents, metadatas=metadatas, embeddings=embeddings
        )

    def delete_by_ids(self, space: str, ids: list[str]) -> None:
        if not ids:
            return
        self._collection(space).delete(ids=ids)

    def delete_by_page(self, space: str, page_path: str) -> None:
        """按页面路径删除全部 chunk（先查 id 再删，兼容性最好）"""
        col = self._collection(space)
        got = col.get(where={"path": page_path})
        ids = (got or {}).get("ids") or []
        if ids:
            col.delete(ids=ids)

    def ids_by_page(self, space: str, page_path: str) -> list[str]:
        got = self._collection(space).get(where={"path": page_path})
        return (got or {}).get("ids") or []

    # ---------- 检索 ----------
    def query(self, space: str, query_embedding: list[float], top_k: int = 10,
              where: dict | None = None) -> list[dict]:
        """返回按相似度降序的命中列表：[{id, path, title, type, content, score, chunk_index}]"""
        col = self._collection(space)
        try:
            res = col.query(
                query_embeddings=[query_embedding],
                n_results=top_k,
                where=where,
                include=["documents", "metadatas", "distances"],
            )
        except Exception as e:  # noqa: BLE001
            logger.warning("chroma query failed: %s", e)
            return []
        if not res or not res.get("ids") or not res["ids"][0]:
            return []

        hits = []
        for i, cid in enumerate(res["ids"][0]):
            meta = (res["metadatas"][0] or [{}] * len(res["ids"][0]))[i] or {}
            score = 1.0 - float(res["distances"][0][i]) if res.get("distances") else 0.0
            hits.append({
                "id": cid,
                "path": meta.get("path", ""),
                "title": meta.get("title", ""),
                "type": meta.get("type", ""),
                "chunk_index": meta.get("chunk_index", 0),
                "content": (res["documents"][0] or [""] * len(res["ids"][0]))[i] or "",
                "score": round(max(0.0, min(1.0, score)), 4),
            })
        return hits
