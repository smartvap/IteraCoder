FROM eclipse-temurin:17-jdk

RUN apt-get update && \
    apt-get install -y maven --no-install-recommends && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# 保持容器运行，等待 docker exec
ENTRYPOINT ["tail", "-f", "/dev/null"]
