# -*- coding: utf-8 -*-
"""纯文本解析器：.txt → ParsedDocument（type=doc）"""
from __future__ import annotations

import os

from .base import DocumentParser, ParsedDocument, register_parser


@register_parser
class TextParser(DocumentParser):
    extensions = {".txt"}

    def parse_text(self, raw: str, file_name: str = "") -> ParsedDocument:
        title = os.path.splitext(file_name)[0] if file_name else ""
        return ParsedDocument(
            title=title,
            metadata={"type": "doc", "tags": []},
            content=raw or "",
            raw=raw or "",
        )

    def serialize(self, doc: ParsedDocument) -> str:
        # 纯文本无 frontmatter，原样回写
        return doc.raw or doc.content or ""
