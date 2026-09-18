# -*- coding: utf-8 -*-
"""路径安全：防止目录穿越，确保路径落在指定根目录内"""
import os
from pathlib import Path


class UnsafePathError(ValueError):
    pass


def safe_join(root: str | os.PathLike, relative_path: str) -> Path:
    """将 relative_path 安全拼接到 root 下；越界则抛 UnsafePathError。

    - 支持多级相对路径，如 concepts/投诉.md
    - 拒绝绝对路径、.. 穿越、空路径
    """
    rel = relative_path.replace("\\", "/")
    if not rel or rel.startswith("/") or rel.startswith("."):
        raise UnsafePathError(f"非法相对路径: {relative_path!r}")
    # Windows 盘符/UNC 防护
    if ":" in rel.split("/")[0]:
        raise UnsafePathError(f"非法相对路径: {relative_path!r}")

    root_path = Path(root).resolve()
    target = (root_path / rel).resolve()
    if not str(target).startswith(str(root_path)):
        raise UnsafePathError(f"路径越界: {relative_path!r}")
    return target
