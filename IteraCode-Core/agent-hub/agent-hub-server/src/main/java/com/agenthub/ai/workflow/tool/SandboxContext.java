package com.agenthub.ai.workflow.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SandboxTools 上下文：ThreadLocal + 静态 Map 双保险。
 * <p>
 * StateGraph 的并行节点可能在不同线程执行，ThreadLocal 无法跨线程传递。
 * 静态 Map 作为兜底，SandboxTools 优先 ThreadLocal，未命中则查 Map。
 */
public final class SandboxContext {

    private static final ThreadLocal<String> PROJECT_ROOT = new ThreadLocal<>();
    private static final ThreadLocal<String> CONTAINER_ID = new ThreadLocal<>();
    private static final Map<String, String> GLOBAL_MAP = new ConcurrentHashMap<>();

    private static final String KEY_PROJECT = "projectRoot";
    private static final String KEY_CONTAINER = "containerId";
    /**
     * 全局 containerId Map（按 threadId 索引），解决 ThreadLocal 跨线程失效问题。
     * StateGraph 的节点（如 harnessAgent）可能在不同线程执行，
     * ThreadLocal 无法跨线程传递 containerId。
     * SandboxTools 通过 SseStreamingInterceptor.getActiveThreadId 获取 threadId，
     * 再从此 Map 获取对应的 containerId。
     */
    private static final Map<String, String> GLOBAL_CONTAINER_MAP = new ConcurrentHashMap<>();

    /** 最近一次沙箱失败摘要（供 RepairCountIncrementNode 写入 State） */
    private static volatile String lastErrorDetail;
    /** 修复 Agent 通过 writeCodeFile 写的临时目录（替代 code_project_write） */
    private static final ThreadLocal<String> REPAIR_PROJECT_ROOT = new ThreadLocal<>();
    /**
     * 全局修复目录 Map（按 threadId 索引），解决 ThreadLocal 跨线程失效问题。
     * StateGraph 的条件边和后续节点可能在不同线程执行，
     * ThreadLocal 无法跨线程传递 repairProjectRoot。
     */
    private static final Map<String, String> GLOBAL_REPAIR_ROOT = new ConcurrentHashMap<>();
    /**
     * 初始代码生成目录 Map（按 threadId 索引）。
     * 修复 Agent 首次写文件时，需要先把初始项目的完整文件复制到修复目录，
     * 否则修复目录只有被修改的几个文件，缺少其他源文件导致编译失败。
     */
    private static final Map<String, String> INITIAL_PROJECT_ROOT = new ConcurrentHashMap<>();

    public static void setRepairProjectRoot(String root) {
        REPAIR_PROJECT_ROOT.set(root);
    }

    public static String getRepairProjectRoot() {
        return REPAIR_PROJECT_ROOT.get();
    }

    /**
     * 按 threadId 存储修复目录，供条件边和其他线程的节点读取。
     */
    public static void setRepairProjectRootForThread(String threadId, String root) {
        GLOBAL_REPAIR_ROOT.put(threadId, root);
        REPAIR_PROJECT_ROOT.set(root);
    }

    /**
     * 按 threadId 获取修复目录，优先 ThreadLocal，未命中则查全局 Map。
     */
    public static String getRepairProjectRootForThread(String threadId) {
        String local = REPAIR_PROJECT_ROOT.get();
        if (local != null) {
            return local;
        }
        if (threadId != null) {
            return GLOBAL_REPAIR_ROOT.get(threadId);
        }
        return null;
    }

    public static void clearRepairProjectRoot() {
        REPAIR_PROJECT_ROOT.remove();
    }

    // ===== 初始代码生成目录（供修复 Agent 首次创建修复目录时复制基础文件）=====

    public static void setInitialProjectRoot(String threadId, String root) {
        INITIAL_PROJECT_ROOT.put(threadId, root);
    }

    public static String getInitialProjectRoot(String threadId) {
        return threadId != null ? INITIAL_PROJECT_ROOT.get(threadId) : null;
    }

    /**
     * 按 threadId 清理修复目录，防止跨工作流泄漏。
     */
    public static void clearRepairProjectRootForThread(String threadId) {
        REPAIR_PROJECT_ROOT.remove();
        if (threadId != null) {
            GLOBAL_REPAIR_ROOT.remove(threadId);
        }
    }

    public static void init(String projectRoot, String containerId) {
        PROJECT_ROOT.set(projectRoot);
        CONTAINER_ID.set(containerId);
        GLOBAL_MAP.put(KEY_PROJECT, projectRoot);
        GLOBAL_MAP.put(KEY_CONTAINER, containerId);
    }

    /**
     * 按 threadId 存储容器 ID，供 SandboxTools 跨线程获取。
     */
    public static void initForThread(String threadId, String projectRoot, String containerId) {
        init(projectRoot, containerId);
        if (threadId != null && containerId != null) {
            GLOBAL_CONTAINER_MAP.put(threadId, containerId);
        }
    }

    /**
     * 按 threadId 获取容器 ID，优先 ThreadLocal，未命中则查全局 Map。
     */
    public static String getContainerIdForThread(String threadId) {
        String local = CONTAINER_ID.get();
        if (local != null) {
            return local;
        }
        if (threadId != null) {
            return GLOBAL_CONTAINER_MAP.get(threadId);
        }
        return null;
    }

    /**
     * 按 threadId 清理容器 ID，防止跨工作流泄漏。
     */
    public static void clearContainerIdForThread(String threadId) {
        CONTAINER_ID.remove();
        GLOBAL_MAP.remove(KEY_CONTAINER);
        if (threadId != null) {
            GLOBAL_CONTAINER_MAP.remove(threadId);
        }
    }

    public static String getProjectRoot() {
        String v = PROJECT_ROOT.get();
        return v != null ? v : GLOBAL_MAP.get(KEY_PROJECT);
    }

    public static String getContainerId() {
        String v = CONTAINER_ID.get();
        return v != null ? v : GLOBAL_MAP.get(KEY_CONTAINER);
    }

    public static void setLastErrorDetail(String detail) {
        lastErrorDetail = detail;
    }

    public static String getAndClearLastErrorDetail() {
        String v = lastErrorDetail;
        lastErrorDetail = null;
        return v;
    }

    public static void clear() {
        PROJECT_ROOT.remove();
        CONTAINER_ID.remove();
        GLOBAL_MAP.remove(KEY_PROJECT);
        GLOBAL_MAP.remove(KEY_CONTAINER);
        REPAIR_PROJECT_ROOT.remove();
    }

    public static void clearAll(String threadId) {
        clear();
        clearRepairProjectRootForThread(threadId);
        clearContainerIdForThread(threadId);
        if (threadId != null) {
            INITIAL_PROJECT_ROOT.remove(threadId);
        }
    }
}
