# -*- coding: utf-8 -*-
"""知识页管理路由（CRUD + 增量同步 + 上传，sync/upload 为异步后台任务）"""
from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, File, Form, HTTPException, Query, Request, UploadFile, status

from ..models import PageCreate, PageOut
from ..services.parsers import get_parser, supported_extensions
from .errors import as_http

router = APIRouter(prefix="/spaces/{space}/pages", tags=["pages"])


@router.get("", response_model=list[dict])
def list_pages(space: str, request: Request,
               type: Optional[str] = Query(default=None, alias="type")) -> list[dict]:
    try:
        return request.app.state.services.page_service.list(space, page_type=type)
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e


@router.get("/{path:path}", response_model=PageOut)
def get_page(space: str, path: str, request: Request) -> PageOut:
    try:
        data = request.app.state.services.page_service.get(space, path)
        if not data:
            raise HTTPException(status_code=404, detail=f"页面不存在: {path}")
        return PageOut(**data)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e


@router.post("", response_model=PageOut, status_code=status.HTTP_201_CREATED)
def create_page(space: str, body: PageCreate, request: Request) -> PageOut:
    try:
        idx = request.app.state.services.page_service.create(space, body.path, body.content)
        data = request.app.state.services.page_service.get(space, idx["path"])
        return PageOut(**data)
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e


@router.put("/{path:path}", response_model=PageOut)
def update_page(space: str, path: str, body: PageCreate, request: Request) -> PageOut:
    """body.path 会被忽略（以 URL path 为准）"""
    try:
        idx = request.app.state.services.page_service.update(space, path, body.content)
        data = request.app.state.services.page_service.get(space, idx["path"])
        return PageOut(**data)
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e


@router.delete("/{path:path}", status_code=status.HTTP_204_NO_CONTENT)
def delete_page(space: str, path: str, request: Request) -> None:
    try:
        request.app.state.services.page_service.delete(space, path)
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e


@router.post("/sync", status_code=status.HTTP_202_ACCEPTED)
def sync_pages(space: str, request: Request) -> dict:
    """增量同步（异步）：立即 202 + task_id，后台执行扫描 wiki/{space} ↔ 台账 diff。

    空间不存在仍同步返回 404；任务完成后 GET /tasks/{task_id} 可查
    result={"space", "added", "updated", "deleted", "skipped", "errors",
    "skipped_extensions"}。
    """
    svc = request.app.state.services
    if not svc.space_service.get(space):
        raise HTTPException(status_code=404, detail=f"空间不存在: {space}")
    task_id = svc.task_manager.submit(
        "sync", space, lambda: svc.page_service.sync(space))
    return {"task_id": task_id, "kind": "sync", "space": space, "status": "running"}


@router.post("/upload", status_code=status.HTTP_202_ACCEPTED)
async def upload_page(space: str, request: Request,
                      file: UploadFile = File(...),
                      overwrite: bool = Form(False),
                      subdir: str = Form("")) -> dict:
    """多格式上传（异步）：前置校验通过后立即 202 + task_id，后台解析入库。

    - 同步前置校验：文件名非空 / 空间存在 / 扩展名受支持（同 import_document）
    - 文件字节在请求内读完（data=bytes），任务函数不再持有请求期文件对象
    - 目标已存在且 overwrite=false → 不再 HTTP 409，任务 status=error、
      error="页面已存在: xxx"（前端先做存在性预检再决定 overwrite）
    """
    svc = request.app.state.services
    if not file.filename:
        raise HTTPException(status_code=422, detail="上传文件缺少文件名")
    file_name = os.path.basename((file.filename or "").replace("\\", "/"))
    if get_parser(file_name) is None:
        ext = os.path.splitext(file_name)[1].lower()
        raise HTTPException(
            status_code=422,
            detail=f"不支持的格式: {ext or '(无扩展名)'}"
                   f"（支持 {', '.join(sorted(supported_extensions()))}）",
        )
    if not svc.space_service.get(space):
        raise HTTPException(status_code=404, detail=f"空间不存在: {space}")

    data = await file.read()
    task_id = svc.task_manager.submit(
        "upload", space,
        lambda: svc.page_service.import_document(
            space, file_name, data, subdir=subdir, overwrite=overwrite),
    )
    return {"task_id": task_id, "kind": "upload", "space": space, "status": "running"}
