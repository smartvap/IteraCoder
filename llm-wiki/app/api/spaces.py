# -*- coding: utf-8 -*-
"""知识空间管理路由"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request, status

from ..models import SpaceCreate, SpaceOut
from .errors import as_http

router = APIRouter(prefix="/spaces", tags=["spaces"])


@router.post("", response_model=SpaceOut, status_code=status.HTTP_201_CREATED)
def create_space(body: SpaceCreate, request: Request) -> SpaceOut:
    try:
        return SpaceOut(**request.app.state.services.space_service.create(
            body.name, types=body.types))
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e


@router.get("", response_model=list[SpaceOut])
def list_spaces(request: Request) -> list[SpaceOut]:
    return [SpaceOut(**d) for d in request.app.state.services.space_service.list()]


@router.get("/{name}", response_model=SpaceOut)
def get_space(name: str, request: Request) -> SpaceOut:
    svc = request.app.state.services.space_service
    data = svc.get(name)
    if not data:
        raise HTTPException(status_code=404, detail=f"空间不存在: {name}")
    return SpaceOut(**data)


@router.delete("/{name}", status_code=status.HTTP_204_NO_CONTENT)
def delete_space(name: str, request: Request) -> None:
    svc = request.app.state.services.space_service
    try:
        svc.delete(name)
    except Exception as e:  # noqa: BLE001
        raise as_http(e) from e
