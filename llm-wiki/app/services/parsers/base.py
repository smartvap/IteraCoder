# -*- coding: utf-8 -*-
"""文档解析抽象层：支持可扩展的文件格式

设计要点：
- 每种文件格式 = 一个 DocumentParser 实现（注册到 PARSER_REGISTRY）
- P1 提供 MarkdownParser（frontmatter + 正文）
- 后续扩展 docx/pdf/txt 等：新增 Parser 类并注册即可，业务层无感

解析产物统一为 ParsedDocument：
  title / metadata(扩展字段) / content(纯文本正文) / raw(原始内容)
"""
from __future__ import annotations

import os
from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class ParsedDocument:
    title: str = ""
    metadata: dict = field(default_factory=dict)  # 含 type/tags 等扩展字段
    content: str = ""  # 纯文本正文（用于分块与向量化）
    raw: str = ""      # 原始内容（保留原样，用于序列化回写）


class DocumentParser(ABC):
    """文档解析器基类：实现 extend/parse_text/parse_file/serialize 即可"""

    #: 支持的扩展名，如 {".md", ".markdown"}
    extensions: set[str] = set()

    @abstractmethod
    def parse_text(self, raw: str, file_name: str = "") -> ParsedDocument:
        """将原始文本解析为 ParsedDocument"""

    @abstractmethod
    def serialize(self, doc: ParsedDocument) -> str:
        """将 ParsedDocument 序列化为文件文本（回写时用）"""

    def parse_file(self, file_path: str) -> ParsedDocument:
        with open(file_path, "r", encoding="utf-8") as f:
            raw = f.read()
        return self.parse_text(raw, file_name=os.path.basename(file_path))

    def parse_bytes(self, data: bytes, file_name: str = "") -> ParsedDocument:
        """解析二进制文档（docx/pdf/pptx/xlsx）。默认不支持则抛 NotImplementedError"""
        raise NotImplementedError(f"{type(self).__name__} 不支持二进制解析")


# ---------------- 注册表 ----------------
_PARSER_REGISTRY: dict[str, type[DocumentParser]] = {}


def register_parser(cls: type[DocumentParser]) -> type[DocumentParser]:
    for ext in cls.extensions:
        _PARSER_REGISTRY[ext.lower()] = cls
    return cls


def get_parser(ext_or_path: str) -> DocumentParser | None:
    """按扩展名获取解析器实例（文件名/路径均可）"""
    ext = os.path.splitext(ext_or_path)[1].lower()
    cls = _PARSER_REGISTRY.get(ext)
    return cls() if cls else None


def supported_extensions() -> list[str]:
    return sorted(_PARSER_REGISTRY.keys())
