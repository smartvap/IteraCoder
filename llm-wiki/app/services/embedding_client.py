# -*- coding: utf-8 -*-
"""Embedding 客户端：通过 Ollama OpenAI 兼容接口调用 embedding 模型

支持：/v1/embeddings（OpenAI 协议），批量向量化 + 维度自动探测。
"""
from __future__ import annotations

import logging
from functools import lru_cache

from openai import OpenAI

logger = logging.getLogger(__name__)


class EmbeddingClient:
    def __init__(self, base_url: str, api_key: str, model: str, batch_size: int = 32):
        self.model = model
        self.batch_size = batch_size
        self._client = OpenAI(base_url=base_url, api_key=api_key)
        self._dim: int | None = None

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        """批量向量化（分批发送，返回与入参顺序一致的向量列表）"""
        if not texts:
            return []
        results: list[list[float]] = []
        for i in range(0, len(texts), self.batch_size):
            batch = texts[i : i + self.batch_size]
            resp = self._client.embeddings.create(model=self.model, input=batch)
            # 按入参顺序取出（OpenAI 返回 data 顺序与 input 一致）
            ordered = sorted(resp.data, key=lambda x: x.index)
            results.extend([item.embedding for item in ordered])
        if results and self._dim is None:
            self._dim = len(results[0])
        return results

    @property
    def dimension(self) -> int | None:
        return self._dim

    def health_check(self) -> bool:
        """探测 embedding 服务是否可用（用空文本单条调用）"""
        try:
            resp = self._client.embeddings.create(model=self.model, input=["ping"])
            return bool(resp.data)
        except Exception as e:  # noqa: BLE001
            logger.warning("embedding health check failed: %s", e)
            return False


@lru_cache
def get_embedding_client() -> EmbeddingClient | None:
    """单例；不可用返回 None（调用方决定是否降级）"""
    from ..config import get_settings

    s = get_settings()
    try:
        return EmbeddingClient(
            base_url=s.embedding_base_url,
            api_key=s.embedding_api_key,
            model=s.embedding_model,
            batch_size=s.embedding_batch_size,
        )
    except Exception as e:  # noqa: BLE001
        logger.error("init embedding client failed: %s", e)
        return None
