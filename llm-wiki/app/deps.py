# -*- coding: utf-8 -*-
"""服务组装与依赖注入

全局单例容器：settings / meta_store / vector_store / space_service /
page_service / retriever / task_manager / embedding_client。
FastAPI 路由通过 request.app.state.services 访问。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

from .config import Settings, get_settings
from .services.embedding_client import get_embedding_client
from .services.meta_store import MetaStore
from .services.page_service import PageService
from .services.retriever import Retriever
from .services.space_service import SpaceService
from .services.task_manager import TaskManager
from .services.vector_store import VectorStore

logger = logging.getLogger(__name__)


@dataclass
class Services:
    settings: Settings
    meta: MetaStore
    vectors: VectorStore
    space_service: SpaceService
    page_service: PageService
    retriever: Retriever
    task_manager: TaskManager
    embedding_client: object = field(default=None)
    embedding_ready: bool = False


def build_services() -> Services:
    s = get_settings()
    s.ensure_dirs()

    meta = MetaStore(s.meta_db_path)
    vectors = VectorStore(s.chroma_dir)
    space_svc = SpaceService(s, meta, vectors)
    emb = get_embedding_client()

    embedding_ready = False
    if emb is not None:
        try:
            embedding_ready = emb.health_check()
        except Exception:  # noqa: BLE001
            embedding_ready = False
        if embedding_ready:
            logger.info("Embedding 服务可用: model=%s dim=%s",
                        emb.model, emb.dimension)
        else:
            logger.warning("Embedding 服务不可用(%s) —— 检索将降级为纯 BM25",
                           s.embedding_base_url)
    else:
        logger.warning("Embedding 客户端初始化失败 —— 检索将降级为纯 BM25")

    page_svc = PageService(s, meta, vectors, space_svc, emb)
    retriever = Retriever(s, meta, vectors, emb)
    task_manager = TaskManager()
    return Services(
        settings=s, meta=meta, vectors=vectors,
        space_service=space_svc, page_service=page_svc,
        retriever=retriever, task_manager=task_manager,
        embedding_client=emb, embedding_ready=embedding_ready,
    )
