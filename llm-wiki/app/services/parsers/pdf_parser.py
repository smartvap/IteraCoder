# -*- coding: utf-8 -*-
"""pdf 解析器：pypdf 逐页抽取文本 → 纯文本

扫描件（无文字层）extract_text 往往返回极少文本，属预期行为，
由上层提示用户文件缺少可解析文字。
"""
from __future__ import annotations

import os
from io import BytesIO

from pypdf import PdfReader

from .base import DocumentParser, ParsedDocument, register_parser


@register_parser
class PdfParser(DocumentParser):
    extensions = {".pdf"}

    def parse_text(self, raw: str, file_name: str = "") -> ParsedDocument:
        raise NotImplementedError("pdf 为二进制格式，请使用 parse_bytes")

    def parse_bytes(self, data: bytes, file_name: str = "") -> ParsedDocument:
        try:
            reader = PdfReader(BytesIO(data))
        except Exception as e:  # noqa: BLE001
            raise ValueError(f"pdf 解析失败: {e}") from e

        pages: list[str] = []
        for page in reader.pages:
            text = (page.extract_text() or "").strip()
            if text:
                pages.append(text)

        content = "\n\n".join(pages)
        title = os.path.splitext(file_name)[0]
        return ParsedDocument(
            title=title, metadata={"type": "doc", "tags": []},
            content=content, raw="",
        )

    def serialize(self, doc: ParsedDocument) -> str:
        raise NotImplementedError("pdf 解析器不支持反向序列化")
