# llm-wiki 通用知识库服务

agent-hub 的**知识库 B（人工维护）**后端：基于知识空间的文档型知识库，提供
**页面管理 + 混合检索（BM25 + 向量）+ 增量同步 + 多格式文档导入**，通过 FastAPI 对外服务化。

> 前身为 my-llm-wiki（dizuo 业务线专用）；本工程为**全新重写**，通用化、多业务线隔离，
> 服务化接入 agent-hub / ChatSQL 智能体。

## 架构

```
agent-hub-server(Java) ──HTTP──→ llm-wiki (Python FastAPI + ChromaDB + SQLite)
                                    │
                          data/chroma/    ← 向量（每知识空间一 collection）
                          data/wiki/{space}/ ← 知识页 md（frontmatter + 正文）
                          data/meta.db      ← SQLite 台账（增量同步基石）
```

## 核心设计

| 概念 | 说明 |
|------|------|
| **知识空间（Space）** | 多业务线隔离单元；每空间独立目录 + Chroma collection + 台账；新增业务 = 建空间，零代码改动 |
| **页面（Page）** | markdown + frontmatter（title/type/tags）；支持任意相对路径（含子目录）；内容格式由 DocumentParser 抽象层决定 |
| **多格式导入（upload）** | 支持 `.md/.markdown/.txt/.docx/.pdf/.pptx/.xlsx`；非 md 文档**归一化为 md 知识页**后入库，可编辑可检索 |
| **混合检索** | BM25（jieba 分词） + 向量（Ollama embedding） → RRF 融合；embedding 不可用自动降级纯 BM25 |
| **增量同步 sync** | 扫描磁盘文件 ↔ 台账 content_hash diff → 新增/更新/删除；避免全量重建 |
| **异步任务（TaskManager）** | upload/sync 后台执行，接口秒回 `task_id`，前端轮询 `GET /tasks/{task_id}`；同空间写任务串行 |

## 快速开始（本地）

```bash
python -m venv .venv
.venv\Scripts\activate            # Windows
pip install -r requirements.txt

# 确保 Ollama 可用且有 embedding 模型（默认 qwen3-embedding:0.6b）
ollama pull qwen3-embedding:0.6b

uvicorn app.main:app --host 0.0.0.0 --port 8000
# 文档: http://localhost:8000/docs
```

## Docker 部署

```bash
# 前提：宿主机已装 Ollama 并 pull embedding 模型
docker compose up -d --build
# 数据卷 ./data 持久化；容器通过 host.docker.internal 访问宿主机 Ollama
```

## API 概览（前缀 /api/v1）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST/GET/DELETE | `/spaces` | 知识空间管理 |
| GET/POST | `/spaces/{s}/pages` | 页面列表 / 新建 |
| GET/PUT/DELETE | `/spaces/{s}/pages/{path}` | 页面详情 / 更新 / 删除 |
| POST | `/spaces/{s}/pages/upload` | 上传文档（md/txt/docx/pdf/pptx/xlsx）→ **异步任务**，返回 task_id |
| POST | `/spaces/{s}/pages/sync` | 增量同步（文件↔台账 diff）→ **异步任务**，返回 task_id |
| GET | `/tasks/{task_id}` | 异步任务状态查询（running/completed/error + result） |
| POST | `/spaces/{s}/search` | 混合检索 `{query, top_k, filters}` |
| GET | `/health`、`/api/v1/health` | 健康检查（含 embedding 就绪态、支持的格式） |

> 上传/同步为异步模型：**发起立即返回 202 + `task_id`**，真正的扫描/解析/embedding 在后台线程执行；
> 用 `GET /tasks/{task_id}` 轮询状态。任务保存在进程内存（重启丢失），同空间写任务串行。

### 使用示例

```bash
# 1. 建空间
curl -X POST localhost:8000/api/v1/spaces \
  -H "Content-Type: application/json" \
  -d '{"name":"chatsql","types":["concept","metric","faq","doc"]}'

# 2. 加页面（markdown 含 frontmatter）
curl -X POST localhost:8000/api/v1/spaces/chatsql/pages \
  -H "Content-Type: application/json" \
  -d '{"path":"concepts/投诉.md","content":"---\ntitle: 投诉\ntype: concept\ntags: [客户,投诉]\n---\n投诉指客户对营业服务的正式不满反馈..."}'

# 3. 上传 Word 文档（multipart；非 md 自动归一化为 md 页面，如 员工手册.docx → 员工手册.md）
curl -X POST localhost:8000/api/v1/spaces/chatsql/pages/upload \
  -F "file=@员工手册.docx" -F "overwrite=false" -F "subdir="
#  → {"task_id":"t_...","kind":"upload","space":"chatsql","status":"running"}

# 4. 查询异步任务结果（轮询直到 completed/error）
curl localhost:8000/api/v1/tasks/t_...
# completed 时 result = {"path":"员工手册.md","content_hash":"...","chunk_count":N,"created":true}
# 已存在且 overwrite=false → status=error，error="页面已存在: xxx"

# 5. 检索
curl -X POST localhost:8000/api/v1/spaces/chatsql/search \
  -H "Content-Type: application/json" \
  -d '{"query":"什么是投诉","top_k":5}'

# 6. 同步（手动编辑磁盘文件后调用；异步，返回 task_id 后轮询）
curl -X POST localhost:8000/api/v1/spaces/chatsql/pages/sync
```

## 环境变量（.env / Docker env）

| 变量 | 默认 | 说明 |
|------|------|------|
| LLM_WIKI_DATA_DIR | ./data | 运行时数据根（Docker 卷挂载） |
| LLM_WIKI_EMBEDDING_BASE_URL | http://localhost:11434/v1 | Ollama OpenAI 兼容端点 |
| LLM_WIKI_EMBEDDING_MODEL | qwen3-embedding:0.6b | embedding 模型 |
| LLM_WIKI_EMBEDDING_BATCH_SIZE | 32 | embedding 批量大小 |
| LLM_WIKI_CHUNK_SIZE / OVERLAP | 2000 / 200 | 分块参数 |
| LLM_WIKI_RETRIEVER_TOP_K / BM25_TOP_K / VECTOR_TOP_K / RRF_K | 5 / 10 / 10 / 60 | 检索参数 |
| LLM_WIKI_PORT | 8000 | 服务端口 |

## 目录结构

```
app/
├── main.py            # FastAPI 入口（含 /health /api/v1/health）
├── config.py          # pydantic-settings（环境变量）
├── deps.py            # 服务组装（单例容器，含 TaskManager）
├── models/            # Pydantic 模型
├── api/               # 路由（spaces/pages/search/tasks + 错误映射）
├── services/
│   ├── task_manager.py      # 异步任务：线程池 + 空间级互斥 + 200 条内存记录
│   ├── space_service.py     # 空间生命周期
│   ├── page_service.py      # 页面 CRUD + 导入 + 增量同步
│   ├── vector_store.py      # ChromaDB 封装
│   ├── embedding_client.py  # Ollama embedding
│   ├── retriever.py         # BM25 + 向量 + RRF
│   ├── meta_store.py        # SQLite 台账
│   └── parsers/             # DocumentParser 注册表
│       ├── markdown_parser.py  # .md/.markdown（frontmatter）
│       ├── text_parser.py      # .txt
│       ├── docx_parser.py      # .docx（python-docx）
│       ├── pdf_parser.py       # .pdf（pypdf）
│       ├── pptx_parser.py      # .pptx（python-pptx）
│       └── xlsx_parser.py      # .xlsx（openpyxl）
└── utils/             # hash / 分块 / 路径安全
```

依赖新增：`python-multipart`（上传）、`python-docx`、`pypdf`、`python-pptx`、`openpyxl`（富文档解析）。

## 与 agent-hub / ChatSQL 集成

- ChatSQL 知识检索（知识库 B 通道）→ 调 `/api/v1/spaces/{s}/search`
- agent-hub 管理页（`llmwiki.html`）→ 经 `/api/v1/llmwiki/**` 代理本服务 API（页面 CRUD / 上传 / sync / 异步任务轮询）
- 反馈闭环 → 通过 API 沉淀 FAQ / 概念页

## Roadmap

- [x] P1：FastAPI 骨架 + 知识空间 + ChromaDB 存储 + SQLite 台账 + 混合检索 + Docker
- [x] P2：多格式文档 import（docx/pdf/pptx/xlsx/txt → md 归一化）+ 上传接口
- [x] P2.5：upload/sync 异步任务化（TaskManager + 轮询）
- [ ] P3：lint 健康检查 / embedding 缓存落盘 / Rerank（bge-reranker）/ 问答沉淀
- [ ] P4（扩展性）：十万级 chunk 批量 embedding 提速、大文件进度上报、任务持久化、PDF OCR
