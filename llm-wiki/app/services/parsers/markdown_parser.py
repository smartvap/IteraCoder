# -*- coding: utf-8 -*-
"""Markdown 解析器：frontmatter(title/type/tags 等) + 正文"""
from __future__ import annotations

import os

import frontmatter

from .base import DocumentParser, ParsedDocument, register_parser


@register_parser
class MarkdownParser(DocumentParser):
    extensions = {".md", ".markdown"}

    def parse_text(self, raw: str, file_name: str = "") -> ParsedDocument:
        post = frontmatter.loads(raw)
        meta = dict(post.metadata or {})
        title = str(meta.get("title") or "").strip()
        if not title and file_name:
            title = os.path.splitext(file_name)[0]
        page_type = str(meta.get("type") or "doc").strip() or "doc"
        tags = meta.get("tags") or []
        if isinstance(tags, str):
            tags = [t.strip() for t in tags.split(",") if t.strip()]
        meta["type"] = page_type
        meta["tags"] = list(tags)
        return ParsedDocument(
            title=title, metadata=meta, content=post.content or "", raw=raw
        )

    def serialize(self, doc: ParsedDocument) -> str:
        meta = dict(doc.metadata or {})
        if doc.title and not meta.get("title"):
            meta["title"] = doc.title
        meta.setdefault("type", "doc")
        post = frontmatter.Post(doc.content or "", **meta)
        return frontmatter.dumps(post)
