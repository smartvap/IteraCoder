package com.agenthub.ai.workflow.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DinD 安全沙箱 Docker 服务：管理容器生命周期（创建/执行/销毁）。
 * <p>
 * DinD 模式下自动拉起 docker:dind 特权容器，沙箱在内部 Docker 引擎中执行。
 * <p>
 * 文件传递使用 tar 流上传（不依赖宿主机挂载）。
 */
@Slf4j
@Service
public class DockerSandboxService {

    @Value("${agenthub.workflow.sandbox.docker-host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${agenthub.workflow.sandbox.image-name:agent-hub/sandbox:latest}")
    private String imageName;

    @Value("${agenthub.workflow.sandbox.network-enabled:true}")
    private boolean networkEnabled;

    @Value("${agenthub.workflow.sandbox.memory-mb:512}")
    private long memoryMb;

    @Value("${agenthub.workflow.sandbox.cpu-limit:1.0}")
    private double cpuLimit;

    @Value("${agenthub.workflow.sandbox.timeout-seconds:120}")
    private int defaultTimeoutSeconds;

    @Value("${agenthub.workflow.sandbox.build-response-timeout-seconds:1800}")
    private int buildResponseTimeoutSeconds;

    @Value("${agenthub.workflow.sandbox.dind-enabled:false}")
    private boolean dindEnabled;

    @Value("${agenthub.workflow.sandbox.dind-image:docker:28-dind}")
    private String dindImage;

    @Value("${agenthub.workflow.sandbox.dind-container-name:agent-hub-dind}")
    private String dindContainerName;

    @Value("${agenthub.workflow.sandbox.dind-expose-port:2375}")
    private int dindExposePort;

    private DockerClient hostClient;
    private DockerClient sandboxClient;
    private final Map<String, String> containerCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        hostClient = buildClient(dockerHost);
        if (hostClient == null) {
            log.warn("宿主机 Docker 不可用 ({}), 沙箱编译/运行/测试将返回 FAILED，请启动 Docker 后重试", dockerHost);
            return;
        }

        if (dindEnabled) {
            sandboxClient = startDinD(hostClient);
            if (sandboxClient == null) {
                log.warn("DinD 初始化失败，回退到宿主机 Docker");
                sandboxClient = hostClient;
            } else {
                // 同步沙箱镜像到 DinD（宿主机没有则自动构建）
                try {
                    syncImageToDinD(hostClient, sandboxClient, imageName, true);
                } catch (Exception e) {
                    log.error("沙箱镜像同步到 DinD 失败，回退到宿主机 Docker: {}", e.getMessage());
                    sandboxClient = hostClient;
                }
                // 同步基础镜像到 DinD（宿主机没有则抛异常中断，强制环境要求）
                try {
                    syncImageToDinD(hostClient, sandboxClient, "eclipse-temurin:17-jdk", false);
                } catch (Exception e) {
                    // @PostConstruct 不能声明受检异常，包装为运行时异常中断启动
                    throw new IllegalStateException("基础镜像同步到 DinD 失败，中断启动: " + e.getMessage(), e);
                }
            }
        } else {
            sandboxClient = hostClient;
        }

        ensureSandboxImage(sandboxClient);
        log.info("Docker 沙箱就绪: mode={}", dindEnabled ? "DinD" : "Host");
    }

    @PreDestroy
    public void destroy() {
        containerCache.values().forEach(id -> destroyForce(id));
        containerCache.clear();
        close(sandboxClient);
        if (sandboxClient != hostClient) close(hostClient);
    }

    public boolean isAvailable() {
        return sandboxClient != null;
    }

    // ===== 公开 API =====

    public String createSandbox(String threadId, String projectRoot) {
        if (sandboxClient == null) throw new IllegalStateException("Docker 不可用");
        if (projectRoot == null || projectRoot.isBlank()) throw new IllegalArgumentException("projectRoot 为空");

        String name = "agent-hub-sandbox-" + threadId.substring(0, 8);
        containerCache.remove(threadId);
        destroyByName(name);

        String containerId = doCreateSandbox(name, projectRoot);
        containerCache.put(threadId, containerId);
        log.info("沙箱容器已创建: name={}", name);
        return containerId;
    }

    /**
     * 复用已有沙箱容器（修复循环多轮间保持容器存活，利用 Maven 缓存提速）。
     * <p>
     * 如果容器存在且运行中 → 仅上传最新项目文件，容器内 Maven 本地仓库保留。
     * 如果容器不存在或已停止 → 全量创建新容器。
     *
     * @return 容器 ID
     */
    public String reuseOrCreateSandbox(String threadId, String projectRoot) {
        if (sandboxClient == null) throw new IllegalStateException("Docker 不可用");
        if (projectRoot == null || projectRoot.isBlank()) throw new IllegalArgumentException("projectRoot 为空");

        String existingId = containerCache.get(threadId);
        if (existingId != null) {
            try {
                var inspect = sandboxClient.inspectContainerCmd(existingId).exec();
                if (inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    // 容器存活，只上传最新文件
                    uploadProject(existingId, projectRoot);
                    log.info("沙箱容器复用: id={}, 仅上传项目文件", existingId.substring(0, 8));
                    return existingId;
                }
            } catch (Exception e) {
                log.warn("沙箱容器已失效 (id={}), 将重建: {}", existingId.substring(0, 8), e.getMessage());
            }
        }

        // 容器不存在或已失效 → 全量创建
        String name = "agent-hub-sandbox-" + threadId.substring(0, 8);
        String containerId = doCreateSandbox(name, projectRoot);
        containerCache.put(threadId, containerId);
        log.info("沙箱容器已重建: name={}, id={}", name, containerId.substring(0, 8));
        return containerId;
    }

    /** 创建容器 + 上传项目文件（内部方法，不含缓存管理） */
    private String doCreateSandbox(String name, String projectRoot) {
        destroyByName(name);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(memoryMb * 1024 * 1024)
                .withNanoCPUs((long) (cpuLimit * 1_000_000_000L))
                .withNetworkMode(networkEnabled ? "bridge" : "none")
                .withAutoRemove(true);

        CreateContainerResponse container;
        try {
            container = sandboxClient.createContainerCmd(imageName)
                    .withName(name).withHostConfig(hostConfig).exec();
        } catch (com.github.dockerjava.api.exception.ConflictException e) {
            // 409 名称冲突：destroyByName 未删干净（如容器正在移除），强制删除 + 等待 + 重试
            log.warn("沙箱创建 409 名称冲突，强制清理后重试: name={}, err={}", name, e.getMessage());
            destroyByName(name);
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            container = sandboxClient.createContainerCmd(imageName)
                    .withName(name).withHostConfig(hostConfig).exec();
        }
        sandboxClient.startContainerCmd(container.getId()).exec();

        uploadProject(container.getId(), projectRoot);
        return container.getId();
    }

    public ExecResult exec(String containerId, int timeoutSeconds, String... command) {
        if (sandboxClient == null) return new ExecResult(-1, "", "Docker 不可用");

        ExecCreateCmdResponse execCreate = sandboxClient.execCreateCmd(containerId)
                .withCmd(command).withAttachStdout(true).withAttachStderr(true).exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            sandboxClient.execStartCmd(execCreate.getId())
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            try {
                                (frame.getStreamType() == StreamType.STDERR ? stderr : stdout)
                                        .write(frame.getPayload());
                            } catch (IOException ignored) {}
                        }
                    }).awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            Long exitCodeLong = sandboxClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
            int code = exitCodeLong != null ? exitCodeLong.intValue() : -1;
            return new ExecResult(code, stdout.toString(), stderr.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, stdout.toString(), "执行被中断");
        } catch (Exception e) {
            return new ExecResult(-1, stdout.toString(), "执行异常: " + e.getMessage());
        }
    }

    public void destroySandbox(String threadId) {
        String id = containerCache.remove(threadId);
        if (id != null) destroyForce(id);
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean success() { return exitCode == 0; }
    }

    // ===== DinD 管理 =====

    private DockerClient buildClient(String host) {
        try {
            DockerClientConfig cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(host).build();
            DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                    .dockerHost(cfg.getDockerHost()).maxConnections(10)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofSeconds(buildResponseTimeoutSeconds)).build();
            DockerClient c = DockerClientImpl.getInstance(cfg, http);
            c.pingCmd().exec();
            return c;
        } catch (Exception e) {
            log.warn("Docker 连接失败 ({}): {}", host, e.getMessage());
            return null;
        }
    }

    private DockerClient startDinD(DockerClient host) {
        log.info("启动 DinD 容器...");
        try {
            List<Container> existing = host.listContainersCmd()
                    .withShowAll(true).withNameFilter(List.of(dindContainerName)).exec();
            boolean running = existing.stream().anyMatch(c -> "running".equals(c.getState()));
            if (!running) {
                existing.forEach(c -> { try { host.removeContainerCmd(c.getId()).withForce(true).exec(); } catch (Exception ignored) {} });
                // 检查镜像：不存在则先尝试 pull，失败后用内置 Dockerfile 自动构建
                try {
                    host.inspectImageCmd(dindImage).exec();
                } catch (Exception e) {
                    log.info("DinD 镜像 {} 不存在，尝试拉取...", dindImage);
                    boolean pulled = false;
                    try {
                        boolean completed = host.pullImageCmd(dindImage)
                                .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.PullResponseItem>() {
                                    @Override
                                    public void onNext(com.github.dockerjava.api.model.PullResponseItem item) {
                                        if (item.getStatus() != null && !item.getStatus().isBlank()) {
                                            String id = item.getId();
                                            String progress = item.getProgress();
                                            if (progress != null && !progress.isBlank()) {
                                                log.info("[Docker Pull] {} {} {}",
                                                        id != null ? id + ": " : "", item.getStatus(), progress);
                                            } else {
                                                log.info("[Docker Pull] {} {}",
                                                        id != null ? id + ": " : "", item.getStatus());
                                            }
                                        }
                                        super.onNext(item);
                                    }
                                })
                                .awaitCompletion(60, TimeUnit.SECONDS);
                        // 修复点1：awaitCompletion 超时返回 false，不代表拉取成功
                        // 修复点2：即使返回 true，也要用 inspect 验证镜像真的存在
                        if (completed) {
                            host.inspectImageCmd(dindImage).exec();
                            pulled = true;
                        } else {
                            log.warn("拉取 DinD 镜像超时（60s），改用内置 Dockerfile 构建: {}", dindImage);
                        }
                    } catch (Exception pullEx) {
                        log.warn("拉取 DinD 镜像失败或超时（{}），改用内置 Dockerfile 构建: {}", pullEx.getMessage(), dindImage);
                    }
                    if (!pulled) {
                        buildDindImage(host);
                    }
                }
                ExposedPort port = ExposedPort.tcp(dindExposePort);
                Ports bindings = new Ports();
                bindings.bind(port, Ports.Binding.bindPort(dindExposePort));
                CreateContainerResponse c = host.createContainerCmd(dindImage)
                        .withName(dindContainerName).withExposedPorts(port)
                        .withHostConfig(HostConfig.newHostConfig()
                                .withPortBindings(bindings).withPrivileged(true))
                        .withCmd("--host=tcp://0.0.0.0:" + dindExposePort, "--tls=false").exec();
                host.startContainerCmd(c.getId()).exec();
                log.info("DinD 容器已启动: id={}", c.getId().substring(0, 8));
            }
            // 等待内部 Docker 就绪（从 dockerHost 提取主机部分，支持 tcp://host:port 格式）
            String dindHostname = "localhost";
            if (dockerHost != null && dockerHost.startsWith("tcp://")) {
                String rest = dockerHost.substring("tcp://".length());
                int colon = rest.lastIndexOf(':');
                if (colon > 0) {
                    dindHostname = rest.substring(0, colon);
                }
            }
            String dindHost = "tcp://" + dindHostname + ":" + dindExposePort;
            for (int i = 0; i < 15; i++) {
                DockerClient inner = buildClient(dindHost);
                if (inner != null) {
                    log.info("DinD 内部 Docker 就绪");
                    return inner;
                }
                Thread.sleep(2000);
            }
            log.error("DinD 内部 Docker 启动超时");
            return null;
        } catch (Exception e) {
            log.error("DinD 初始化异常", e);
            return null;
        }
    }

    /**
     * 将宿主 Docker 中的镜像同步到 DinD 内部引擎。
     *
     * @param host       宿主机 DockerClient
     * @param inner      DinD 内部 DockerClient
     * @param image      镜像名（如 agent-hub/sandbox:latest 或 eclipse-temurin:17-jdk）
     * @param autoBuildOnHost 宿主没有该镜像时是否自动构建（沙箱镜像=true，基础镜像=false）
     * @throws Exception 基础镜像缺失或同步失败时抛出（中断启动）
     */
    private void syncImageToDinD(DockerClient host, DockerClient inner, String image, boolean autoBuildOnHost) throws Exception {
        // 1. DinD 内部已有 → 跳过
        try {
            inner.inspectImageCmd(image).exec();
            log.info("DinD 内部已有镜像: {}", image);
            return;
        } catch (Exception ignored) {}

        // 2. 确保宿主机有该镜像
        try {
            host.inspectImageCmd(image).exec();
        } catch (Exception e) {
            if (autoBuildOnHost) {
                // 沙箱镜像：宿主机没有则自动构建（宿主机有加速器，能拉 Docker Hub）
                log.warn("宿主机无沙箱镜像 {}，自动构建: {}", image, e.getMessage());
                buildSandboxImage(host);
            } else {
                // 基础镜像：宿主机没有 → 抛异常中断启动（强制环境要求）
                throw new IllegalStateException("宿主机缺少基础镜像 " + image + "，请先在宿主机执行: docker pull " + image);
            }
        }

        // 3. save 导出 → load 导入
        try (java.io.InputStream tarStream = host.saveImageCmd(image).exec()) {
            inner.loadImageCmd(tarStream).exec();
            log.info("镜像已从宿主同步到 DinD: {}", image);
        }

        // 4. 验证 DinD 内部存在
        try {
            inner.inspectImageCmd(image).exec();
            log.info("验证 DinD 内部镜像存在: {}", image);
        } catch (Exception e) {
            throw new IllegalStateException("镜像同步后 DinD 内部验证失败: " + image, e);
        }
    }

    private void ensureSandboxImage(DockerClient client) {
        // 1. 已存在 → 直接返回
        try {
            client.inspectImageCmd(imageName).exec();
            log.info("沙箱镜像已就绪: {}", imageName);
            return;
        } catch (Exception ignored) {}

        // 2. 尝试自动构建（使用内置 Dockerfile）
        try {
            buildSandboxImage(client);
            return;
        } catch (Exception e) {
            log.error("自动构建沙箱镜像失败: {}", e.getMessage());
        }
        log.warn("沙箱镜像 {} 不存在且自动构建失败", imageName);
    }

    private void buildSandboxImage(DockerClient client) throws Exception {
        buildImageFromDockerfile(client, imageName, "docker/sandbox/Dockerfile");
    }

    /** 使用内置 Dockerfile 构建 DinD 镜像（镜像层禁用 TLS） */
    private void buildDindImage(DockerClient client) throws Exception {
        buildImageFromDockerfile(client, dindImage, "docker/dind/Dockerfile");
    }

    /**
     * 通用镜像构建：从 classpath 读取内置 Dockerfile，打成仅含 Dockerfile 的 tar 上下文，
     * 通过 docker build 构建并打上指定 tag（带实时日志输出）。
     */
    private void buildImageFromDockerfile(DockerClient client, String tag, String dockerfileResourcePath) throws Exception {
        log.info("使用内置 Dockerfile 构建镜像: {}", tag);
        // 读取内置 Dockerfile
        java.io.InputStream dockerfileStream = getClass().getClassLoader()
                .getResourceAsStream(dockerfileResourcePath);
        if (dockerfileStream == null) {
            throw new RuntimeException("内置 Dockerfile 未找到: " + dockerfileResourcePath);
        }
        String dockerfileContent = new String(dockerfileStream.readAllBytes());
        dockerfileStream.close();

        // 构建 tar 上下文（仅含 Dockerfile）
        ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarBytes)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
            byte[] content = dockerfileContent.getBytes();
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }

        // 执行 docker build（带日志输出，解决构建卡住时无法知道进度的问题）
        client.buildImageCmd()
                .withTarInputStream(new java.io.ByteArrayInputStream(tarBytes.toByteArray()))
                .withTag(tag)
                .exec(new com.github.dockerjava.api.command.BuildImageResultCallback() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.BuildResponseItem item) {
                        // 普通输出行（如 "Step 1/4 : FROM ..."）
                        String msg = item.getStream();
                        if (msg != null && !msg.isBlank()) {
                            log.info("[Docker Build] {}", msg.stripTrailing());
                        }
                        // 拉取/下载进度（如 "Pulling from ..." / "Downloading"）
                        String status = item.getStatus();
                        if (status != null && !status.isBlank()) {
                            String id = item.getId();
                            String progress = item.getProgress();
                            if (progress != null && !progress.isBlank()) {
                                log.info("[Docker Build] {} {} {}", 
                                        (id != null ? id + ": " : ""), status, progress);
                            } else {
                                // 无进度条时手动拼 current/total
                                var detail = item.getProgressDetail();
                                if (detail != null && detail.getTotal() != null && detail.getTotal() > 0) {
                                    long cur = detail.getCurrent() != null ? detail.getCurrent() : 0;
                                    log.info("[Docker Build] {} {} {}/{}",
                                            (id != null ? id + ": " : ""), status, cur, detail.getTotal());
                                } else {
                                    log.info("[Docker Build] {} {}",
                                            (id != null ? id + ": " : ""), status);
                                }
                            }
                        }
                        String err = item.getError();
                        if (err != null && !err.isBlank()) {
                            log.warn("[Docker Build][ERROR] {}", err.stripTrailing());
                        }
                        super.onNext(item);
                    }
                })
                .awaitCompletion();

        // 验证构建成功；失败时打印完整堆栈便于排查，再向上抛出（保留原有调用方语义）
        try {
            client.inspectImageCmd(tag).exec();
        } catch (Exception e) {
            log.error("镜像构建失败: {}，详细错误见上方 [Docker Build] 日志", tag, e);
            throw e;
        }
        log.info("镜像构建成功: {}", tag);
    }

    private void close(DockerClient c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }

    // ===== tar 上传 =====

    private void uploadProject(String containerId, String projectRoot) {
        try {
            byte[] tar = createTar(projectRoot);
            sandboxClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new java.io.ByteArrayInputStream(tar))
                    .withRemotePath("/workspace").exec();
            log.info("项目上传沙箱: {} → /workspace ({} KB)", projectRoot, tar.length / 1024);
        } catch (IOException e) {
            throw new RuntimeException("上传项目失败", e);
        }
    }

    private byte[] createTar(String root) throws IOException {
        Path rootPath = Path.of(root);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Path rel = rootPath.relativize(f);
                    TarArchiveEntry e = new TarArchiveEntry(f.toFile(), rel.toString().replace("\\", "/"));
                    tar.putArchiveEntry(e); Files.copy(f, tar); tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    if (d.equals(rootPath)) return FileVisitResult.CONTINUE;
                    String name = rootPath.relativize(d).toString().replace("\\", "/") + "/";
                    TarArchiveEntry e = new TarArchiveEntry(name); e.setSize(0);
                    tar.putArchiveEntry(e); tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return bos.toByteArray();
    }

    // ===== 容器清理 =====

    private void destroyForce(String containerId) {
        try { sandboxClient.killContainerCmd(containerId).exec(); } catch (Exception ignored) {}
        try { sandboxClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception ignored) {}
        // 等待容器完全移除（最多 5 秒），避免立即重建时 409 冲突
        try {
            for (int i = 0; i < 5; i++) {
                Thread.sleep(1000);
                try {
                    sandboxClient.inspectContainerCmd(containerId).exec();
                } catch (Exception notFound) {
                    return; // 已移除成功
                }
            }
        } catch (Exception ignored) {}
    }

    private void destroyByName(String name) {
        try {
            String norm = name.startsWith("/") ? name : "/" + name;
            sandboxClient.listContainersCmd().withShowAll(true).exec().stream()
                    .filter(c -> {
                        if (c.getNames() == null) return false;
                        for (String n : c.getNames()) {
                            if (norm.equals(n) || name.equals(n.replaceFirst("^/", ""))) return true;
                        }
                        return false;
                    })
                    .forEach(c -> destroyForce(c.getId()));
        } catch (Exception e) {
            log.warn("删除沙箱容器失败（按名称 {}）: {}", name, e.getMessage());
        }
    }
}
