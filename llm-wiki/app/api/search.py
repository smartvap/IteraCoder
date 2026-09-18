# -*- coding: utf-8 -*-
"""检索路由"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request

from ..models import SearchRequest
from .errors import as_http

router = APIRouter(prefix="/spaces/{space}", tags=["search"])


@router.post("/search", response_model=list[dict])
def search(space: str, body: SearchRequest, request: Request) -> list[dict]:
    svc = request.app.state.services
    if not svc.space_service.get(space):
        raise HTTPException(status_code=404, detail=f"空间不存在: {space}")
    try:
        return svc.retriever.search(
            space, body.query, top_k=body.top_k, filters=body.filters)
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e
