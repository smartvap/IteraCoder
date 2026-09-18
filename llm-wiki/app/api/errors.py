# -*- coding: utf-8 -*-
"""统一异常 → HTTP 映射"""
from fastapi import HTTPException

from ..services.page_service import PageError
from ..services.space_service import SpaceError


def as_http(e: Exception) -> HTTPException:
    """将业务异常转为 HTTPException（默认 400）"""
    if isinstance(e, (PageError, SpaceError)):
        return HTTPException(status_code=getattr(e, "code", 400), detail=str(e))
    return HTTPException(status_code=500, detail=f"内部错误: {e}")
