package com.agenthub.ai.workflow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.agenthub.ai.workflow.exception.WorkflowInfraException;

/**
 * Git 项目推送服务。
 * <p>
 * 工作流 COMPLETED 后将项目代码推送到远程 Git 仓库（每工作流一个独立仓库）。
 * 每轮修复保留独立 commit，形成完整迭代历史。首次推送前自动通过 API 创建仓库。
 * <p>
 * 使用外部 git 命令（ProcessBuilder），不依赖 JGit。
 * 配置 {@code agenthub.workflow.git.enabled=true} 启用。
 * <p>
 * 支持 Gitea 和 GitLab（通过 api-base-url、token、repo-owner 配置切换）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agenthub.workflow.git.enabled", havingValue = "true", matchIfMissing = false)
public class GitProjectService {

    private final String remoteUrl;
    private final String username;
    private final String email;
    private final String apiBaseUrl;
    private final String token;
    private final String repoOwner;
    private final HttpClient httpClient;

    @Value("${agenthub.workflow.code-storage-path:}")
    private String codeStoragePath;

    public GitProjectService(
            @Value("${agenthub.workflow.git.remote-url}") String remoteUrl,
            @Value("${agenthub.workflow.git.username:agent-hub}") String username,
            @Value("${agenthub.workflow.git.email:agent@hub.local}") String email,
            @Value("${agenthub.workflow.git.api-base-url:}") String apiBaseUrl,
            @Value("${agenthub.workflow.git.token:}") String token,
            @Value("${agenthub.workflow.git.repo-owner:agent-hub}") String repoOwner) {
        this.remoteUrl = removeTrailingSlash(remoteUrl);
        this.username = username;
        this.email = email;
        this.apiBaseUrl = removeTrailingSlash(apiBaseUrl);
        this.token = token.isBlank() ? null : token;
        this.repoOwner = repoOwner.isBlank() ? "agent-hub" : repoOwner;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        log.info("Git 推送已启用: remoteUrl={}, apiUrl={}, repoOwner={}",
                this.remoteUrl, this.apiBaseUrl, this.repoOwner);
    }

    /** 仅在 .git 不存在时执行 git init + 配置用户信息 */
    public void initIfNeeded(Path projectDir) {
        if (!Files.exists(projectDir.resolve(".git"))) {
            exec(projectDir, "git", "init");
        }
        // 确保分支名为 main（兼容 git < 2.28 不支持 -b 参数；
        // 恢复执行复用 .git 时旧分支可能是 master，此处统一重命名）
        execQuiet(projectDir, "git", "branch", "-m", "main");
        exec(projectDir, "git", "config", "user.name", username);
        exec(projectDir, "git", "config", "user.email", email);
    }

    /** git add -A + git commit（无变更时跳过） */
    public void commit(Path projectDir, String message) {
        exec(projectDir, "git", "add", "-A");
        String status = execCapture(projectDir, "git", "status", "--porcelain");
        if (status.isBlank()) {
            // 诊断：列出目录文件，排查文件是否正确写入该目录
            try (var stream = Files.walk(projectDir, 5)) {
                var allPaths = stream.collect(Collectors.toList());
                boolean hasGit = allPaths.stream()
                        .anyMatch(p -> p.startsWith(projectDir.resolve(".git")));
                long srcCount = allPaths.stream()
                        .filter(p -> !p.startsWith(projectDir.resolve(".git")))
                        .count();
                log.warn("Git commit 跳过（无变更）: projectDir={}, .git存在={}, 源码文件数={}",
                        projectDir, hasGit, srcCount);
                if (srcCount > 0) {
                    String srcFiles = allPaths.stream()
                            .filter(p -> !p.startsWith(projectDir.resolve(".git")))
                            .map(p -> projectDir.relativize(p).toString())
                            .limit(50)
                            .collect(Collectors.joining("\n"));
                    log.warn("  源码文件列表=\n{}", srcFiles);
                }
            } catch (Exception e) {
                log.warn("Git commit 跳过（无变更）: projectDir={}", projectDir);
            }
            return;
        }
        log.info("Git commit: projectDir={}, 变更行数={}", projectDir, status.split("\n").length);
        exec(projectDir, "git", "commit", "-m", message);
    }

    /**
     * 推送到远程仓库（每工作流独立仓库）。
     * 首次推送前通过 API 自动创建仓库；仓库已存在则跳过创建。
     * 同步执行，内部重试 3 次（2s/4s/6s 递增），返回是否成功。
     */
    public boolean push(Path projectDir, String threadId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                createRepoIfNeeded(threadId);
                String repoPath = repoOwner.isBlank()
                        ? "projects-" + threadId
                        : repoOwner + "/projects-" + threadId;
                String repoUrl = token != null
                        ? remoteUrl.replace("://", "://" + token + "@") + "/" + repoPath
                        : remoteUrl + "/" + repoPath;
                execQuiet(projectDir, "git", "remote", "remove", "origin");
                exec(projectDir, "git", "remote", "add", "origin", repoUrl);
                String err = execCapture(projectDir, "git", "push", "-u", "origin", "main");
                if (err != null && err.contains("fatal") || err != null && err.contains("denied")
                        || err != null && err.contains("unable")) {
                    log.warn("Git push 失败（第 {} 次）: {}", attempt, err.strip());
                } else {
                    log.info("Git push 成功: {}/{} (token认证)", remoteUrl, repoPath);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Git push 异常（第 {} 次）: {}", attempt, e.getMessage());
            }
            try { Thread.sleep(attempt * 2000L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Git push 连续 3 次失败，等待工作流结束兜底补推: {}", projectDir);
        return false;
    }

    /** 异步推送（不阻塞沙箱/工作流），失败由后续轮次或 pushFinalState 兜底 */
    public void pushAsync(Path projectDir, String threadId) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                initIfNeeded(projectDir);
                commit(projectDir, "push final state for " + threadId);
                push(projectDir, threadId);
            } catch (Exception e) {
                log.warn("Git pushAsync 异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 拉取远程最新代码（修复循环中使用）。
     * .git 不存在或 pull 失败时静默跳过。
     */
    public void pullLatest(Path projectDir, String threadId) {
        if (!Files.exists(projectDir.resolve(".git"))) return;
        try {
            String repoPath = repoOwner.isBlank()
                    ? "projects-" + threadId
                    : repoOwner + "/projects-" + threadId;
            String repoUrl = token != null
                    ? remoteUrl.replace("://", "://" + token + "@") + "/" + repoPath
                    : remoteUrl + "/" + repoPath;
            log.info("Git pull: threadId={}, projectDir={}", threadId, projectDir);
            execQuiet(projectDir, "git", "remote", "set-url", "origin", repoUrl);
            execQuiet(projectDir, "git", "pull", "origin", "main");
        } catch (Exception e) {
            log.debug("Git pull 失败（可忽略）: threadId={}, error={}", threadId, e.getMessage());
        }
    }

    /**
     * 从远程 git 克隆到临时目录，返回目录路径；失败返回 null。
     * 调用方负责清理临时目录。
     */
    public Path cloneToTemp(String threadId) {
        String repoPath = repoOwner.isBlank()
                ? "projects-" + threadId
                : repoOwner + "/projects-" + threadId;
        String repoUrl = token != null
                ? remoteUrl.replace("://", "://" + token + "@") + "/" + repoPath
                : remoteUrl + "/" + repoPath;
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("git-checkout-");
            log.info("Git clone: {} → {}", repoUrl, tmp);
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", repoUrl, tmp.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (p.exitValue() != 0) {
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("Git clone 失败 (exit={}): {} → {}", p.exitValue(), output.trim());
                deleteQuiet(tmp);
                return null;
            }
            return tmp;
        } catch (Exception e) {
            log.warn("Git clone 异常: {}", e.getMessage());
            return null;
        }
    }

    private void deleteQuiet(Path dir) {
        try { java.nio.file.Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} }); }
        catch (Exception ignored) {}
    }

    /**
     * 尝试从远程 Git 克隆仓库到临时目录。
     * <ul>
     *   <li>空仓库/不存在 → 静默返回 null，调用方降级使用 checkpoint 代码</li>
     *   <li>服务器/网络/认证错误 → 抛出 WorkflowInfraException，中断工作流显示错误</li>
     * </ul>
     * @return 克隆目录路径，失败时返回 null
     * @throws WorkflowInfraException 服务器错误
     */
    public Path cloneIfExists(String threadId) {
        String repoPath = repoOwner.isBlank()
                ? "projects-" + threadId
                : repoOwner + "/projects-" + threadId;
        String repoUrl = token != null
                ? remoteUrl.replace("://", "://" + token + "@") + "/" + repoPath
                : remoteUrl + "/" + repoPath;

        String shortId = threadId.length() > 8 ? threadId.substring(0, 8) : threadId;
        Path parentDir = getStorageDir();
        Path cloneDir = parentDir.resolve("gitclone-" + shortId + "-" + System.currentTimeMillis());

        try {
            Files.createDirectories(parentDir);
            exec(parentDir, "git", "clone", repoUrl, cloneDir.getFileName().toString());
            if (isEmptyRepo(cloneDir)) {
                deleteDir(cloneDir);
                log.info("Git clone 跳过（仓库为空），使用 checkpoint 代码, threadId={}", threadId);
                return null;
            }
            log.info("Git clone 成功: threadId={}, dir={}", threadId, cloneDir);
            return cloneDir;
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("empty repository")
                    || msg.contains("does not exist")
                    || msg.contains("not found")
                    || msg.contains("Could not read from remote"))) {
                deleteDirQuiet(cloneDir);
                log.info("Git clone 跳过（仓库为空或不存在），使用 checkpoint 代码, threadId={}", threadId);
                return null;
            }
            deleteDirQuiet(cloneDir);
            throw new WorkflowInfraException("Git clone 失败: " + (msg != null ? msg : "未知错误"), e);
        } catch (Exception e) {
            deleteDirQuiet(cloneDir);
            log.debug("Git clone 失败（可忽略）: threadId={}, error={}", threadId, e.getMessage());
            return null;
        }
    }

    /** 通过 Gitea/GitLab API 创建仓库（409 已存在时跳过） */
    private void createRepoIfNeeded(String threadId) {
        if (apiBaseUrl.isBlank() || token == null || token.isBlank()) {
            log.warn("跳过 API 创建仓库：api-base-url 或 token 未配置");
            return;
        }
        // 先确保组织存在（repoOwner 非空时），409/422 已存在则跳过
        if (!repoOwner.isBlank()) {
            createOrgIfNeeded();
        }
        String repoName = "projects-" + threadId;
        // repoOwner 非空时在组织下创建；否则在 token 所属用户下创建
        String createUrl = repoOwner.isBlank()
                ? apiBaseUrl + "/user/repos"
                : apiBaseUrl + "/orgs/" + repoOwner + "/repos";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(createUrl))
                    .header("Authorization", "token " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"name\":\"" + repoName + "\",\"private\":true,\"auto_init\":false}"))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 201) {
                log.info("仓库已创建: {}/{}/{}", remoteUrl, repoOwner, repoName);
            } else if (code == 409) {
                log.debug("仓库已存在，跳过创建: {}", repoName);
            } else {
                String body = response.body();
                throw new RuntimeException("创建仓库失败: HTTP " + code + " - "
                        + body.substring(0, Math.min(200, body.length())));
            }
        } catch (RuntimeException e) {
            throw e; // API 错误（403 权限不足等）→ 向上传播，中断工作流
        } catch (Exception e) {
            log.warn("API 创建仓库失败（将直接尝试 push）: {}", e.getMessage());
            // 网络异常等不抛，继续尝试 push
        }
    }

    /** 自动创建组织（Gitea: POST /api/v1/orgs，422=已存在则跳过） */
    private void createOrgIfNeeded() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/orgs"))
                    .header("Authorization", "token " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"username\":\"" + repoOwner + "\"}"))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 201) {
                log.info("组织已创建: {}", repoOwner);
            } else if (code == 422) {
                log.debug("组织已存在: {}", repoOwner);
            }
        } catch (Exception e) {
            log.warn("自动创建组织失败（将直接尝试 push）: {}", e.getMessage());
        }
    }

    /**
     * 执行 git 命令（无返回值，失败时抛 RuntimeException）。
     * 超时或非零退出码均视为失败，调用方需 catch 处理。
     */
    private void exec(Path dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("Git 命令超时 (30s): " + String.join(" ", cmd));
            }
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                String output = new String(p.getInputStream().readAllBytes());
                String detail = output.length() > 500 ? output.substring(0, 500) + "..." : output;
                throw new RuntimeException("Git 命令失败 (exit=" + exitCode + "): " + String.join(" ", cmd)
                        + "\n" + detail.trim());
            }
        } catch (IOException e) {
            throw new RuntimeException("Git 命令执行失败: " + String.join(" ", cmd) + ", " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Git 命令被中断: " + String.join(" ", cmd), e);
        }
    }

    /**
     * 执行 git 命令（静默模式，失败不抛异常）。
     * 用于已知可能失败的命令（如 git remote remove 首次执行时 remote 不存在）。
     */
    private void execQuiet(Path dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 预期内失败，忽略
        }
    }

    private String execCapture(Path dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(15, TimeUnit.SECONDS);
            return new String(p.getInputStream().readAllBytes());
        } catch (Exception e) {
            log.debug("Git 命令执行失败（capture）: cmd={}, err={}",
                    String.join(" ", cmd), e.getMessage());
            return "";
        }
    }

    /** 检查克隆的仓库是否为空（仅包含 .git，无源码文件） */
    private static boolean isEmptyRepo(Path dir) {
        if (!Files.isDirectory(dir)) return true;
        try (var stream = Files.list(dir)) {
            return stream.allMatch(p -> p.getFileName().toString().equals(".git"));
        } catch (Exception e) {
            return true;
        }
    }

    /** 递归删除目录（用于清理失败的 clone 临时目录） */
    private static void deleteDir(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (Exception ignored) {}
                        });
            }
        } catch (Exception ignored) {}
    }

    /** 删除目录（静默，不抛异常） */
    private static void deleteDirQuiet(Path dir) {
        try { deleteDir(dir); } catch (Exception ignored) {}
    }

    private static String removeTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private Path getStorageDir() {
        if (codeStoragePath != null && !codeStoragePath.isBlank())
            return java.nio.file.Path.of(codeStoragePath).normalize();
        return java.nio.file.Path.of(System.getProperty("user.dir"), "..", "temp").normalize();
    }
}
