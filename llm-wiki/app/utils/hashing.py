# -*- coding: utf-8 -*-
"""哈希工具"""
import hashlib


def sha256(text: str) -> str:
    """内容哈希（增量同步的 diff 依据）"""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()
