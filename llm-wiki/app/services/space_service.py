# -*- coding: utf-8 -*-
"""知识空间生命周期服务：创建/查询/删除空间"""
from __future__ import annotations

import os
import re
import shutil

from ..config import Settings

# Chroma collection 命名约束：3-63 位，首字符字母数字
SPACE_NAME_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{1,61}$")


class SpaceError(ValueError):
    pass


class SpaceService:
    def __init__(self, settings: Settings, meta_store, vector_store):
        self.s = settings
        self.meta = meta_store
        self.vectors = vector_store

    # ---------- 校验 ----------
    def validate_name(self, name: str) -> None:
        if not SPACE_NAME_RE.match(name):
            raise SpaceError(
                "空间名需为 2-62 位字母/数字/._-，且首字符为字母或数字（兼容 Chroma collection 命名）"
            )

    def _space_dir(self, name: str) -> str:
        """空间 wiki 目录（已确保 name 合法，防穿越）"""
        return os.path.join(self.s.wiki_dir, name)

    # ---------- 操作 ----------
    def create(self, name: str, types: list[str] | None = None) -> dict:
        name = (name or "").strip()
        self.validate_name(name)
        if self.meta.get_space(name):
            raise SpaceError(f"空间已存在: {name}")

        types = types or ["concept", "metric", "faq", "doc"]
        os.makedirs(self._space_dir(name), exist_ok=True)
        os.makedirs(os.path.join(self.s.raw_dir, name), exist_ok=True)
        self.vectors.create_space(name)
        self.meta.create_space(name, types, collection_name=name)
        return self.meta.get_space(name)  # type: ignore[return-value]

    def get(self, name: str) -> dict | None:
        return self.meta.get_space(name)

    def list(self) -> list[dict]:
        return self.meta.list_spaces()

    def delete(self, name: str) -> None:
        if not self.meta.get_space(name):
            raise SpaceError(f"空间不存在: {name}")
        self.vectors.delete_space(name)
        self.meta.delete_space(name)
        # 删除目录（best-effort）
        for d in (self._space_dir(name), os.path.join(self.s.raw_dir, name)):
            if os.path.isdir(d):
                shutil.rmtree(d, ignore_errors=True)
