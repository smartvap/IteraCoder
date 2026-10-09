package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataQuestionDetector;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * RCAgent 意图节点适配器（rca1.4.0.0 metadata-qa-routing）。
 *
 * <p>职责：包装存量 {@link RcaIntentNode}，在委托 LLM 之前插入「元数据/结构类问题」的确定性判定短路，
 * 命中即以 {@code intent_type=CHAT} 返回（<b>不调用 LLM</b>），使「表结构/索引/主键/外键」类问题经既有
 * {@code CHAT → rcagent_answer} 路径用知识库证据作答，而不是生成 {@code information_schema} 查询被安全体系 BLOCK。</p>
 *
 * <p>三分支：B1 确定性命中 → 发布事件并返回 4 键 Map（不调 LLM）；B2 未命中 → 逐键委托存量节点（LLM 兜底，零回归）；
 * B3 判定异常或判定器为 null → 记录告警后降级为委托（判定失败不阻断主链路）。存量节点本身零改动。</p>
 *
 * <p>约束：不新增 State 键、不新增图节点/条件边；{@code intent_result} 第一行必须为 {@code CHAT}
 * （{@code RcaWorkflowGraphConfig} 条件边依赖该标签集合）；日志只输出 decision/ruleCode/category 与截断后的问题原文，
 * 禁止输出 AGENTS.md 4.3/5.4 所列凭据类信息（口令、密钥、数据库账号与连接串）。</p>
 */
@Slf4j
public class RcaIntentNodeAdapter implements NodeAction {

    /** 意图标签：知识问答（元数据/结构类问题复用该标签，不新增 METADATA 类型） */
    private static final String INTENT_CHAT = "CHAT";
    /** 既有工作流运行态标签 */
    private static final String STATUS_RUNNING = "RUNNING";
    /** 确定性判定路径的进度横幅文案（与存量节点保持一致的前置提示） */
    private static final String MESSAGE_INTENT_START = "正在识别意图...";
    /** 确定性判定路径的完成横幅文案 */
    private static final String MESSAGE_INTENT_DONE = "意图识别完成：CHAT（元数据问答）";
    /** 日志中问题原文最大保留长度（AGENTS.md 5.3：不全量落原文） */
    private static final int LOG_QUESTION_MAX = 80;
    /** 降级告警中问题原文最大保留长度 */
    private static final int WARN_QUESTION_MAX = 200;

    private final WorkflowEventBus eventBus;
    private final RcaIntentNode delegate;
    private final RcaMetadataQuestionDetector detector;

    /**
     * @param eventBus SSE 事件发布（可为 null，发布前判空）
     * @param delegate 存量意图节点（未命中确定性判定时委托）
     * @param detector 元数据问题判定器（为 null 时降级为直接委托，等价改造前行为）
     */
    public RcaIntentNodeAdapter(WorkflowEventBus eventBus,
                                RcaIntentNode delegate,
                                RcaMetadataQuestionDetector detector) {
        this.eventBus = eventBus;
        this.delegate = delegate;
        this.detector = detector;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");

        // B3 前置：判定器缺失（配置异常）→ 直接委托，行为等价改造前
        if (detector == null) {
            return delegate.apply(state);
        }

        RcaMetadataQuestionDetector.Detection detection;
        try {
            detection = detector.detect(question);
        } catch (Exception e) {
            // B3 判定异常降级：不阻断主链路（NFR-BE-002）；仅输出异常摘要与截断问题，避免凭据外泄
            log.warn("RCAgent 元数据判定异常，降级委托 LLM 意图识别, question={}, error={}",
                    RcaNodeUtils.truncate(question, WARN_QUESTION_MAX),
                    RcaNodeUtils.truncate(String.valueOf(e.getMessage()), WARN_QUESTION_MAX));
            return delegate.apply(state);
        }

        // B2 未命中（含查数信号否决）：逐键委托存量节点走 LLM 兜底
        if (detection == null || !detection.metadata()) {
            // 未命中原因（EMPTY_QUESTION / QUERY_SIGNAL:<rule> / NO_RULE_HIT）便于排查，非敏感
            log.info("RCAgent 元数据问答路由：decision={}, reason={}, question={}",
                    "NOT_METADATA", detection == null ? "NULL_DETECTION" : detection.reason(),
                    RcaNodeUtils.truncate(question, LOG_QUESTION_MAX));
            return delegate.apply(state);
        }

        // B1 确定性命中：短路为 CHAT，不调用 LLM
        return shortCircuit(state, question, detection);
    }

    /** B1：命中元数据/结构类问法 → 发布事件并返回 CHAT 结果（不调用 LLM） */
    private Map<String, Object> shortCircuit(OverAllState state, String question,
                                            RcaMetadataQuestionDetector.Detection detection) {
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        log.info("RCAgent 元数据问答路由：decision={}, ruleCode={}, category={}, question={}",
                "METADATA_QUESTION", detection.ruleCode(), detection.category(),
                RcaNodeUtils.truncate(question, LOG_QUESTION_MAX));

        String intentResult = buildIntentResult(detection);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, MESSAGE_INTENT_START, STATUS_RUNNING);
        publish(threadId, RcaWorkflowKeys.INTENT_TYPE, INTENT_CHAT, STATUS_RUNNING);
        publish(threadId, RcaWorkflowKeys.INTENT_RESULT, intentResult, STATUS_RUNNING);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, MESSAGE_INTENT_DONE, STATUS_RUNNING);

        return Map.of(
                RcaWorkflowKeys.INTENT_TYPE, INTENT_CHAT,
                RcaWorkflowKeys.INTENT_RESULT, intentResult,
                RcaWorkflowKeys.WORKFLOW_STATUS, STATUS_RUNNING,
                RcaWorkflowKeys.WORKFLOW_MESSAGE, MESSAGE_INTENT_DONE
        );
    }

    /**
     * 确定性路径 intent_result 文本（逐字符契约）：第一行 {@code CHAT}（条件边依赖），
     * 第二行固定标注命中规则码与元数据类别，供前端展示与排查；不新增 State 键。
     */
    private static String buildIntentResult(RcaMetadataQuestionDetector.Detection detection) {
        return INTENT_CHAT + "\n"
                + "确定性判定：命中元数据/结构类问法（规则码=" + detection.ruleCode()
                + "，类别=" + detection.category()
                + "），路由至知识库问答（不生成 SQL）。";
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
