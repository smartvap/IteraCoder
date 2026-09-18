FROM python:3.11-slim

WORKDIR /app

# 安装依赖（先复制 requirements 以利用层缓存）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

# 复制应用代码
COPY app ./app

# 运行时数据目录（通过 volume 挂载持久化）
RUN mkdir -p /app/data

ENV LLM_WIKI_DATA_DIR=/app/data
ENV PYTHONUNBUFFERED=1

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
