# -*- coding: utf-8 -*-
"""混合检索器：BM25(关键词) + 向量(语义) → RRF 融合

流程：
  1. 向量检索（chunk 级）→ 聚合成 page 级最高分
  2. BM25 检索（页面全文语料，jieba 分词）→ page 级
  3. RRF（Reciprocal Rank Fusion）融合两个 page 排序 → Top-K
  4. embedding 不可用时自动降级为纯 BM25

BM25 语料缓存：基于台账 (path, content_hash) 快照，写操作后自动失效重建。
"""
from __future__ import annotations

import logging
import os
import re

import jieba
from rank_bm25 import BM25Okapi

from ..utils.path_utils import safe_join

logger = logging.getLogger(__name__)

_STOPWORDS = {
    "的", "了", "是", "在", "和", "与", "及", "或", "有", "一个", "这个", "那个",
    "我们", "你们", "他们", "它", "其", "等", "中", "对", "把", "被", "从", "到",
    "the", "a", "an", "is", "are", "to", "of", "and", "or", "for", "with", "in", "on",
}


def _tokenize(text: str) -> list[str]:
    """中英文混合分词：jieba 中文 + 正则保留英文/数字 token"""
    if not text:
        return []
    tokens: list[str] = []
    for seg in jieba.lcut(text):
        seg = seg.strip().lower()
        if not seg:
            continue
        # 英文/数字片段按词保留，中文整词保留
        for tok in re.findall(r"[a-z0-9]+", seg) if re.search(r"[a-z0-9]", seg) else [seg]:
            if tok and tok not in _STOPWORDS and len(tok) > 1 or (tok and not tok.isascii()):
                tokens.append(tok)
    return tokens


def rrf_merge(rankings: list[list[str]], k: int = 60) -> list[tuple[str, float]]:
    """Reciprocal Rank Fusion：合并多个 page 排序，返回 [(path, rrf_score)] 降序"""
    scores: dict[str, float] = {}
    for ranked in rankings:
        for rank, path in enumerate(ranked):
            scores[path] = scores.get(path, 0.0) + 1.0 / (k + rank + 1)
    return sorted(scores.items(), key=lambda x: x[1], reverse=True)


class Retriever:
    def __init__(self, settings, meta_store, vector_store, embedding_client):
        self.s = settings
        self.meta = meta_store
        self.vectors = vector_store
        self.embedding = embedding_client
        self._bm25_cache: dict[str, tuple[frozenset, BM25Okapi | None, list[str]]] = {}

    # ---------- BM25 语料缓存 ----------
    def _snapshot(self, space: str) -> frozenset:
        rows = self.meta.list_pages(space)
        return frozenset((r["path"], r.get("content_hash") or "") for r in rows)

    def _get_bm25(self, space: str) -> tuple[BM25Okapi | None, list[str]]:
        snap = self._snapshot(space)
        cached = self._bm25_cache.get(space)
        if cached and cached[0] == snap:
            return cached[1], cached[2]

        paths: list[str] = []
        corpus: list[str] = []
        base = os.path.join(self.s.wiki_dir, space)
        for path, _ in snap:
            try:
                full = safe_join(base, path)
                with open(full, "r", encoding="utf-8") as f:
                    text = f.read()
                paths.append(path)
                corpus.append(text)
            except Exception:  # noqa: BLE001
                logger.debug("BM25 读取页面失败: %s", path)
                continue
        model: BM25Okapi | None = None
        if corpus:
            model = BM25Okapi([_tokenize(t) for t in corpus])
        self._bm25_cache[space] = (snap, model, paths)
        return model, paths

    # ---------- 主检索 ----------
    def search(self, space: str, query: str, top_k: int | None = None,
               filters: dict | None = None) -> list[dict]:
        query = (query or "").strip()
        if not query:
            return []
        top_k = top_k or self.s.retriever_top_k
        filters = filters or {}
        type_filter = filters.get("type")

        # 页面元数据（输出 + BM25 后置过滤用）
        meta_map = {r["path"]: r for r in self.meta.list_pages(space)}
        vec_page_score: dict[str, float] = {}   # path -> best chunk score
        vec_snippets: dict[str, str] = {}       # path -> best chunk content
        vec_page_type: dict[str, str] = {}

        # 1) 向量检索（chunk 级 → page 级聚合）
        embedding_ok = self.embedding is not None
        if embedding_ok:
            try:
                qv = self.embedding.embed_texts([query])[0]
                where = {"type": type_filter} if type_filter else None
                vec_hits = self.vectors.query(
                    space, qv, top_k=self.s.vector_top_k, where=where)
                for h in vec_hits:
                    p = h["path"]
                    if p not in vec_page_score or h["score"] > vec_page_score[p]:
                        vec_page_score[p] = h["score"]
                        vec_snippets[p] = h["content"]
                    vec_page_type.setdefault(p, h.get("type", ""))
            except Exception as e:  # noqa: BLE001
                logger.warning("向量检索失败，降级 BM25: %s", e)
                embedding_ok = False

        # 2) BM25 检索
        bm25, bm25_paths = self._get_bm25(space)
        bm25_ranked: list[str] = []
        if bm25 and bm25_paths:
            scores = bm25.get_scores(_tokenize(query))
            order = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)
            bm25_ranked = [bm25_paths[i] for i in order if scores[i] > 0]

        # 3) 页面级 type 后置过滤（BM25 无法预过滤）
        def pass_filter(p: str) -> bool:
            if not type_filter:
                return True
            t = vec_page_type.get(p) or meta_map.get(p, {}).get("type", "")
            return t == type_filter

        rankings: list[list[str]] = []
        vec_ranked = [p for p, _ in sorted(
            vec_page_score.items(), key=lambda x: x[1], reverse=True)]
        if vec_ranked:
            rankings.append([p for p in vec_ranked if pass_filter(p)])
        bm25_filtered = [p for p in bm25_ranked if pass_filter(p)]
        if bm25_filtered:
            rankings.append(bm25_filtered)

        if not rankings:
            return []

        merged = rrf_merge(rankings, k=self.s.rrf_k)
        results: list[dict] = []
        bm25_hit_set = set(bm25_filtered)
        for path, rrf in merged[:top_k]:
            meta = meta_map.get(path, {})
            snippet = vec_snippets.get(path) or ""
            if not snippet:
                # 仅 BM25 命中：读全文取摘要
                try:
                    full = safe_join(os.path.join(self.s.wiki_dir, space), path)
                    with open(full, "r", encoding="utf-8") as f:
                        snippet = f.read()[:800]
                except Exception:  # noqa: BLE001
                    snippet = ""
            channels = []
            if path in vec_page_score:
                channels.append("vector")
            if path in bm25_hit_set:
                channels.append("bm25")
            results.append({
                "path": path,
                "title": meta.get("title") or path,
                "type": meta.get("type") or vec_page_type.get(path, "doc"),
                "tags": meta.get("tags", []),
                "content": snippet,
                "score": round(rrf, 4),
                "hit_channels": "+".join(channels),
            })
        return results
