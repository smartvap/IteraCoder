# -*- coding: utf-8 -*-
"""llm-wiki 通用知识库服务 —— FastAPI 入口

启动：uvicorn app.main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from .api import pages, search, spaces, tasks
from .config import get_settings
from .deps import build_services
from .services.parsers import supported_extensions

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    settings.ensure_dirs()
    services = build_services()
    app.state.services = services
    logger.info(
        "llm-wiki 启动完成 | data_dir=%s | embedding_ready=%s | 支持格式=%s",
        settings.data_dir, services.embedding_ready, supported_extensions(),
    )
    yield


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="llm-wiki 通用知识库服务",
        description="agent-hub 知识库 B（人工维护）后端：知识空间 + 页面 CRUD + 混合检索 + 增量同步",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],  # 开发期放开；生产由 agent-hub 后端转发并收紧
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(spaces.router, prefix="/api/v1")
    app.include_router(pages.router, prefix="/api/v1")
    app.include_router(search.router, prefix="/api/v1")
    app.include_router(tasks.router, prefix="/api/v1")

    @app.get("/health")
    def health_root():
        return {"service": "llm-wiki", "docs": "/docs", "health": "/api/v1/health"}

    @app.get("/api/v1/health")
    def health(request: Request):
        svc = request.app.state.services
        return {
            "status": "ok",
            "embedding_ready": svc.embedding_ready,
            "spaces": len(svc.space_service.list()),
            "chroma_ready": True,
            "formats": supported_extensions(),
        }

    @app.get("/")
    def root():
        return {"service": "llm-wiki", "docs": "/docs", "health": "/health"}

    return app


app = create_app()
