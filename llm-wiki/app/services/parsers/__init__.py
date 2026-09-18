# -*- coding: utf-8 -*-
"""解析器包入口：import 子模块以触发注册"""
from .base import (
    DocumentParser,
    ParsedDocument,
    get_parser,
    register_parser,
    supported_extensions,
)
from . import markdown_parser  # noqa: F401  (触发 MarkdownParser 注册)
from . import text_parser  # noqa: F401  (触发 TextParser 注册)
from . import docx_parser  # noqa: F401  (触发 DocxParser 注册)
from . import pdf_parser  # noqa: F401  (触发 PdfParser 注册)
from . import pptx_parser  # noqa: F401  (触发 PptxParser 注册)
from . import xlsx_parser  # noqa: F401  (触发 XlsxParser 注册)

__all__ = [
    "DocumentParser",
    "ParsedDocument",
    "get_parser",
    "register_parser",
    "supported_extensions",
    "markdown_parser",
    "text_parser",
    "docx_parser",
    "pdf_parser",
    "pptx_parser",
    "xlsx_parser",
]
