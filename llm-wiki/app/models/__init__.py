# -*- coding: utf-8 -*-
"""Pydantic 模型：空间 / 页面 / 检索"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

SPACE_NAME_PATTERN = r"^[a-zA-Z0-9][a-zA-Z0-9._-]{1,61}$"
DEFAULT_TYPES = ["concept", "metric", "faq", "doc"]


# ---------------- 空间 ----------------
class SpaceCreate(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    name: str = Field(..., pattern=SPACE_NAME_PATTERN,
                      description="知识空间名：2-62 位字母/数字/._-，首字符字母或数字")
    types: list[str] = Field(default_factory=lambda: list(DEFAULT_TYPES),
                             description="允许的页面类型")


class SpaceOut(BaseModel):
    name: str
    types: list[str] = Field(default_factory=list)
    collection_name: str = ""
    page_count: int = 0
    created_at: str = ""


# ---------------- 页面 ----------------
class PageCreate(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=False)

    path: str = Field(..., min_length=1,
                      description="页面相对路径，支持子目录，如 concepts/投诉.md")
    content: str = Field(..., description="页面完整文件内容（markdown 含可选 frontmatter）")


class PageOut(BaseModel):
    path: str
    title: str = ""
    type: str = "doc"
    tags: list[str] = Field(default_factory=list)
    content: str = ""
    content_hash: str = ""
    status: str = "active"
    chunk_count: int = 0
    updated_at: str = ""


# ---------------- 检索 ----------------
class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, description="检索问题/关键词")
    top_k: int = Field(default=5, ge=1, le=50)
    filters: dict[str, str] | None = Field(default=None,
                                           description='过滤条件，如 {"type": "concept"}')
