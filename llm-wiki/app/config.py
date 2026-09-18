# -*- coding: utf-8 -*-
"""llm-wiki 配置：环境变量驱动（前缀 LLM_WIKI_），支持 .env 文件"""
import os
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="LLM_WIKI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # 数据目录（运行时数据根，Docker 卷挂载点）
    data_dir: str = "./data"

    # Embedding（Ollama OpenAI 兼容接口）
    embedding_base_url: str = "http://localhost:11434/v1"
    embedding_api_key: str = "ollama"
    embedding_model: str = "qwen3-embedding:0.6b"
    embedding_batch_size: int = 32
    embedding_dim: int | None = None  # 首次调用后自动探测

    # 分块参数
    chunk_size: int = 2000
    chunk_overlap: int = 200

    # 检索
    retriever_top_k: int = 5
    bm25_top_k: int = 10
    vector_top_k: int = 10
    rrf_k: int = 60

    # 服务
    port: int = 8000

    # ---- 派生路径 ----
    @property
    def chroma_dir(self) -> str:
        return os.path.join(self.data_dir, "chroma")

    @property
    def wiki_dir(self) -> str:
        return os.path.join(self.data_dir, "wiki")

    @property
    def raw_dir(self) -> str:
        return os.path.join(self.data_dir, "raw")

    @property
    def meta_db_path(self) -> str:
        return os.path.join(self.data_dir, "meta.db")

    def ensure_dirs(self) -> None:
        for d in (self.data_dir, self.chroma_dir, self.wiki_dir, self.raw_dir):
            os.makedirs(d, exist_ok=True)


@lru_cache
def get_settings() -> Settings:
    return Settings()
