# -*- coding: utf-8 -*-
"""文本分块：按段落聚合 + 滑动窗口重叠（自实现，避免引入 langchain）

策略：以段落(\n\n)为最小单元累积到 chunk_size，超长单段落按字符硬切；
chunk 间保留 overlap 个字符的尾部文本以维持上下文连贯。
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Chunk:
    index: int
    text: str


def split_text(text: str, chunk_size: int = 2000, chunk_overlap: int = 200) -> list[Chunk]:
    """将正文切分为带序号的 chunk 列表（index 从 0 起，用于向量 id）。"""
    text = (text or "").strip()
    if not text:
        return []

    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    chunks: list[str] = []

    buf = ""
    for para in paragraphs:
        if not buf:
            buf = para
        elif len(buf) + len(para) + 2 <= chunk_size:
            buf += "\n\n" + para
        else:
            chunks.append(buf)
            # overlap：取 buf 尾部 chunk_overlap 字符作为下一块的开头
            tail = buf[-chunk_overlap:] if chunk_overlap > 0 else ""
            buf = (tail + "\n\n" if tail else "") + para

    if buf:
        chunks.append(buf)

    # 超长单段硬切（理论上 buf 内单段最长约 chunk_size + overlap）
    final: list[str] = []
    for c in chunks:
        while len(c) > chunk_size:
            final.append(c[:chunk_size])
            c = c[chunk_size - chunk_overlap:]
        final.append(c)

    return [Chunk(i, t) for i, t in enumerate(final) if t.strip()]
