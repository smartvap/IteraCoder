package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.exception.WorkflowInfraException;
import com.agenthub.ai.workflow.service.DockerSandboxService;
import com.agenthub.ai.workflow.service.GitProjectService;
import com.agenthub.ai.workflow.service.RdWorkflowService;
import com.agenthub.ai.workflow.tool.SandboxContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 沙箱初始化节点：在 harnessAgent 执行前创建沙箱容器并注入上下文。
 * 同时负责 Git 版本管理（每轮修复保留独立 commit）。
 */
@Slf4j
public class SandboxInitNode implements NodeAction {

    private final DockerSandboxService sandboxService;
    private final WorkflowEventBus eventBus;
    private final GitProjectService gitService;

    public SandboxInitNode(DockerSandboxService sandboxService, WorkflowEventBus eventBus,
            GitProjectService gitService) {
        this.sandboxService = sandboxService;
        this.eventBus = eventBus;
        this.gitService = gitService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY)
                .map(Object::toString).orElse("");

        // 修复循环中 codeRepairAgent 通过 writeCodeFile 工具写文件到 repairProjectRoot。
        // 必须优先使用 repairProjectRoot，否则沙箱上传的是初始代码生成的旧目录，
        // 修复 Agent 写的新文件永远不会被编译验证。
        String projectRoot = SandboxContext.getRepairProjectRootForThread(threadId);

        // 首次代码生成流程：repairProjectRoot 为空，使用 code_project_root
        if (projectRoot == null || projectRoot.isBlank()) {
            projectRoot = state.value(CodeProjectWriteNode.CODE_PROJECT_ROOT, "").toString();
        }

        if (projectRoot == null || projectRoot.isBlank()) {
            String msg = "项目目录为空，无法创建沙箱";
            log.error("沙箱初始化失败: {}", msg);
            publishError(state, msg);
            return Map.of();
        }

        String source = SandboxContext.getRepairProjectRootForThread(threadId) != null ? "repair" : "initial";
        log.info("沙箱初始化: projectRoot={}, source={}", projectRoot, source);

        // 推送进度：告知前端沙箱正在初始化
        String containerName = "agent-hub-sandbox-" + (threadId.length() > 8 ? threadId.substring(0, 8) : threadId);
        publishProgress(state, "🛠 沙箱初始化中...\n   目录: " + projectRoot + "\n   来源: " + source);

        // ① Git 版本管理：先 commit 本地同步（快），再 pushAsync 网络异步（不阻塞后续沙箱）
        if (gitService != null && projectRoot != null && !projectRoot.isBlank()) {
            try {
                Path projectPath = Path.of(projectRoot);
                if ("initial".equals(source)) {
                    gitService.initIfNeeded(projectPath);
                    String req = state.value(RdWorkflowKeys.REQUIREMENT).map(Object::toString).orElse("");
                    String shortReq = req.length() > 100 ? req.substring(0, 100) + "..." : req;
                    gitService.commit(projectPath, "代码生成完成 - " + shortReq);
                    gitService.pushAsync(projectPath, threadId);
                } else {
                    // 修复循环：codefix 目录可能没有 .git（resolveRepairRoot 新建时未复制），
                    // 从 codegen 目录复制 .git 保持提交历史连续（不重建丢失历史）
                    if (!Files.exists(projectPath.resolve(".git"))) {
                        String codegenRoot = state.value(CodeProjectWriteNode.CODE_PROJECT_ROOT, "").toString();
                        if (codegenRoot != null && !codegenRoot.isBlank()) {
                            Path codegenGit = Path.of(codegenRoot).resolve(".git");
                            if (Files.exists(codegenGit)) {
                                try (var stream = Files.walk(codegenGit)) {
                                    stream.forEach(sourcePath -> {
                                        try {
                                            Path relative = codegenGit.relativize(sourcePath);
                                            Path targetPath = projectPath.resolve(".git").resolve(relative);
                                            if (Files.isDirectory(sourcePath)) {
                                                Files.createDirectories(targetPath);
                                            } else {
                                                Files.copy(sourcePath, targetPath,
                                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                            }
                                        } catch (Exception e) {
                                            log.warn("复制 .git 文件失败: {}", e.getMessage());
                                        }
                                    });
                                }
                                log.info(".git 从 codegen 复制到 codefix: {} → {}", codegenRoot, projectRoot);
                            }
                        }
                        // 复制后仍不存在才 init（兜底）
                        if (!Files.exists(projectPath.resolve(".git"))) {
                            gitService.initIfNeeded(projectPath);
                        }
                    }
                    // 拉取远程最新代码，确保基于最新提交
                    gitService.pullLatest(projectPath, threadId);
                    int round = Integer.parseInt(state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
                    gitService.commit(projectPath, "修复第 " + round + " 轮");
                    gitService.pushAsync(projectPath, threadId);
                }
            } catch (Exception e) {
                String errMsg = "Git 推送失败: " + e.getMessage();
                log.error(errMsg, e);
                RdWorkflowService.errorStatusDetails.put(threadId, "FAILED — " + errMsg);
                if (eventBus != null) {
                    eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, errMsg, "RUNNING");
                    eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                            "FAILED — " + errMsg, "FAILED");
                }
                throw new WorkflowInfraException(errMsg, e);
            }
        }

        // ② 沙箱上传
        try {
            String containerId = sandboxService.reuseOrCreateSandbox(threadId, projectRoot);
            SandboxContext.initForThread(threadId, projectRoot, containerId);

            // 推送进度：容器创建成功
            String briefId = containerId.length() > 8 ? containerId.substring(0, 8) : containerId;
            publishProgress(state, "✅ 沙箱就绪: name=" + containerName + ", id=" + briefId);
        } catch (Exception e) {
            String msg = "沙箱创建失败: " + e.getMessage();
            log.error("沙箱初始化异常: threadId={}", threadId, e);
            RdWorkflowService.errorStatusDetails.put(threadId, "FAILED — " + msg);
            if (eventBus != null) {
                eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, msg, "RUNNING");
                eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                        "FAILED — " + msg, "FAILED");
            }
            throw new WorkflowInfraException(msg, e);
        }

        return Map.of();
    }

    private void publishError(OverAllState state, String message) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, "harness_result", "❌ " + message, "RUNNING");
        }
    }

    /** 推送沙箱初始化进度到 SSE 横幅（使用增量模式，追加而非覆盖） */
    private void publishProgress(OverAllState state, String message) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publishDelta(threadId, "harness_result", message + "\n", "RUNNING");
        }
    }
}
