package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.crosssource.CrossSourceConstants;
import com.agenthub.ai.rcagent.crosssource.CrossSourceJoinType;
import com.agenthub.ai.rcagent.crosssource.CrossSourceMetadataAggregator;
import com.agenthub.ai.rcagent.crosssource.CrossSourceMetadataAggregator.SourceMetadataEvidence;
import com.agenthub.ai.rcagent.crosssource.CrossSourcePlanParser;
import com.agenthub.ai.rcagent.crosssource.CrossSourcePlanValidator;
import com.agenthub.ai.rcagent.crosssource.CrossSourcePlanValidator.ValidationOutcome;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.support.CrossSourceStateSupport;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 跨源规划节点（module-003 cross-source-join，新增）。
 *
 * <p>分支契约（api-contract §4.2.1 P0~P6）：仅 {@code QUERY} 意图 + 候选源数 &gt; 1 + {@code enabled=true}
 * 时生效；否则直接返回空 Map（**零 publish、零 State 写入**，单源/CHAT/ATTRIBUTION 零回归）。</p>
 *
 * <ul>
 *   <li>P2 源数 &gt; {@code max-sources}：明确拒绝（写 {@code cross_source_note}，不写 {@code join_plan} ⇒ 条件边降级单源）；</li>
 *   <li>P4 用户显式指定（{@code JOIN_HINT}）优先（{@code plan_source=USER_OVERRIDE}），LLM 仅补全过滤/聚合/投影；</li>
 *   <li>P5 解析/校验失败：LLM 计划 → 降级单源；**用户显式指定计划 → 明确 FAILED**（脱敏诊断）；</li>
 *   <li>P6 计划有效：写 {@code join_plan}（截断 {@code plan-max-len}）+ {@code cross_source_note}。</li>
 * </ul>
 *
 * <p><b>异常契约</b>：本节点**永不抛异常**（LLM/元数据异常一律降级单源并记录脱敏日志：仅长度/计数）；
 * 无状态、无可变字段、线程安全。**禁止** publish 任何含 SQL / dsId 的键。</p>
 */
@Slf4j
public class RcaCrossSourcePlanNode implements NodeAction {

    /** 提示词内用户问题截断长度 */
    private static final int PROMPT_QUESTION_MAX_LEN = 1500;
    /** 提示词内检索上下文截断长度 */
    private static final int PROMPT_RETRIEVAL_MAX_LEN = 3000;
    /** 规划横幅 */
    private static final String BANNER_PLANNING = "正在规划跨源关联（逐源元数据融合 + 关联键推断）...";
    /** 用户指定 join 校验失败诊断前缀（脱敏，≤300） */
    private static final String USER_OVERRIDE_FAILED_DETAIL = "跨源规划失败：用户指定的关联键不可用（未生成可执行 SQL）";

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;
    private final String skillInstruction;
    private final CrossSourceMetadataAggregator metadataAggregator;
    private final CrossSourceProperties properties;

    public RcaCrossSourcePlanNode(WorkflowEventBus eventBus, ChatModel chatModel, String skillInstruction,
            CrossSourceMetadataAggregator metadataAggregator, CrossSourceProperties properties) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
        this.skillInstruction = skillInstruction;
        this.metadataAggregator = metadataAggregator;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String threadId = threadId(state);
        try {
            CrossSourceProperties props = effectiveProperties();
            if (!CrossSourceStateSupport.isCrossSourceQuery(state, props)) {
                // P1：非跨源场景直接返回（零 publish、零 State 写入）
                return Map.of();
            }
            List<String> dsIds = CrossSourceStateSupport.dsIds(state);
            String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
            String retrievalContext = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT, "");
            String joinHint = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.JOIN_HINT, "");
            Map<String, String> hint = CrossSourceStateSupport.parseJoinHint(joinHint);

            // P2：源数超上限 → 明确拒绝（不静默截断；不写 join_plan ⇒ 条件边降级单源）
            if (dsIds.size() > props.getMaxSources()) {
                log.info("RCAgent 跨源规划：候选源数 {} 超上限 {}，明确拒绝", dsIds.size(), props.getMaxSources());
                publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE,
                        CrossSourceConstants.NOTE_SOURCE_COUNT_EXCEEDED, "RUNNING");
                publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                        CrossSourceConstants.NOTE_SOURCE_COUNT_EXCEEDED + "，已按主源执行单源查询", "RUNNING");
                return Map.of(RcaWorkflowKeys.CROSS_SOURCE_NOTE,
                        CrossSourceConstants.NOTE_SOURCE_COUNT_EXCEEDED);
            }
            if (dsIds.size() != 2) {
                return degrade(threadId, CrossSourceConstants.DEGRADE_PLAN_INVALID);
            }

            // P3：逐源元数据融合（单源不可用不阻断，仅标注该源「元数据不可用」）
            List<SourceMetadataEvidence> evidences = metadataAggregator == null
                    ? List.of() : metadataAggregator.aggregate(dsIds, question);
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, BANNER_PLANNING, "RUNNING");

            boolean userOverride = !hint.isEmpty();
            boolean userKeysSpecified = hasText(hint.get(CrossSourceStateSupport.HINT_FIELD_LEFT_KEY))
                    && hasText(hint.get(CrossSourceStateSupport.HINT_FIELD_RIGHT_KEY));

            CrossSourceJoinPlan plan;
            if (userKeysSpecified) {
                // P4：用户显式指定 join key 优先；LLM 仅补全过滤/聚合/投影（失败不降级）
                plan = buildOverridePlan(dsIds, hint, props);
                CrossSourceJoinPlan enriched = parseLlmPlan(dsIds, question, retrievalContext, joinHint, evidences, props);
                applyEnrichment(plan, enriched);
            } else {
                CrossSourceJoinPlan llmPlan = parseLlmPlan(dsIds, question, retrievalContext, joinHint, evidences, props);
                if (llmPlan == null) {
                    // LLM 异常 / 空输出 / 非 JSON 输出：降级单源
                    return degrade(threadId, CrossSourceConstants.DEGRADE_LLM_FAILED, true);
                }
                if (llmPlan.getDegradeReason() != null) {
                    return degrade(threadId, llmPlan.getDegradeReason());
                }
                plan = llmPlan;
                applyHintFields(plan, hint, dsIds);
            }
            fillDsIds(plan, dsIds);
            plan.setThreadId(threadId);
            plan.setSourceCount(2);

            ValidationOutcome outcome = CrossSourcePlanValidator.validate(plan, dsIds, evidences, props);
            if (!outcome.valid()) {
                log.info("RCAgent 跨源规划校验未通过: reason={}, userKeysSpecified={}",
                        outcome.degradeReason(), userKeysSpecified);
                if (userKeysSpecified) {
                    // P5b：用户显式指定计划校验失败 → 明确 FAILED（不生成可执行 SQL、不降级）
                    if (threadId != null) {
                        RcaWorkflowService.errorStatusDetails.put(threadId,
                                "FAILED — " + CrossSourceConstants.truncate(USER_OVERRIDE_FAILED_DETAIL, 300));
                    }
                    publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE,
                            CrossSourceConstants.NOTE_USER_KEY_INVALID, "FAILED");
                    publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                            CrossSourceConstants.NOTE_USER_KEY_INVALID, "FAILED");
                    return Map.of(
                            RcaWorkflowKeys.CROSS_SOURCE_NOTE, CrossSourceConstants.NOTE_USER_KEY_INVALID,
                            RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED");
                }
                return degrade(threadId, outcome.degradeReason());
            }

            // P6：计划有效 → 写 join_plan（截断 plan-max-len）+ 跨源说明
            String planJson = CrossSourceConstants.truncate(JSON.toJSONString(plan), props.getPlanMaxLen());
            String note = CrossSourceConstants.truncate(
                    CrossSourceConstants.NOTE_PLAN_OK_PREFIX + dsIds.size()
                            + CrossSourceConstants.NOTE_PLAN_OK_SUFFIX, props.getNoteMaxLen());
            log.info("RCAgent 跨源规划完成: joinType={}, planSource={}, planLength={}, evidenceAvailable={}",
                    plan.getJoinType(), plan.getPlanSource(), planJson.length(),
                    evidences.stream().filter(SourceMetadataEvidence::available).count());
            publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE, note, "RUNNING");
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "跨源关联计划已生成，进入逐源 SQL 生成", "RUNNING");
            return Map.of(
                    RcaWorkflowKeys.JOIN_PLAN, planJson,
                    RcaWorkflowKeys.CROSS_SOURCE_NOTE, note);
        } catch (Exception e) {
            // 「永不抛异常」契约：任何异常一律降级单源（日志仅记异常类名与截断摘要，禁出 SQL/dsId）
            log.warn("RCAgent 跨源规划异常（降级单源）: type={}, error={}", e.getClass().getSimpleName(),
                    CrossSourceConstants.truncate(String.valueOf(e.getMessage()), 200));
            return degrade(threadId, CrossSourceConstants.DEGRADE_LLM_FAILED);
        }
    }

    /** 降级单源（写 CROSS_SOURCE_NOTE，不写 JOIN_PLAN ⇒ 条件边走 degrade → 既有 sql_plan） */
    private Map<String, Object> degrade(String threadId, String degradeReason) {
        return degrade(threadId, degradeReason, false);
    }

    private Map<String, Object> degrade(String threadId, String degradeReason, boolean llmFailed) {
        log.info("RCAgent 跨源规划降级单源: degradeReason={}", degradeReason);
        publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE, CrossSourceConstants.NOTE_DEGRADED, "RUNNING");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                llmFailed ? CrossSourceConstants.NOTE_LLM_FAILED : CrossSourceConstants.NOTE_DEGRADED, "RUNNING");
        return Map.of(RcaWorkflowKeys.CROSS_SOURCE_NOTE, CrossSourceConstants.NOTE_DEGRADED);
    }

    /** 构造用户显式指定计划（planSource=USER_OVERRIDE；用户字段为权威，其余留待 LLM 补全） */
    private CrossSourceJoinPlan buildOverridePlan(List<String> dsIds, Map<String, String> hint,
            CrossSourceProperties props) {
        String joinTypeText = hint.get(CrossSourceStateSupport.HINT_FIELD_JOIN_TYPE);
        CrossSourceJoinType joinType = CrossSourceJoinType.fromText(joinTypeText);
        String leftDsId = resolveDsId(hint.get(CrossSourceStateSupport.HINT_FIELD_LEFT_DS_ID), dsIds, 1);
        String rightDsId = resolveDsId(hint.get(CrossSourceStateSupport.HINT_FIELD_RIGHT_DS_ID), dsIds, 2);
        return CrossSourceJoinPlan.builder()
                .joinType(joinType == null ? CrossSourceJoinType.INNER : joinType)
                .leftSourceIndex(indexOf(dsIds, leftDsId))
                .rightSourceIndex(indexOf(dsIds, rightDsId))
                .leftDsId(leftDsId)
                .rightDsId(rightDsId)
                .leftKey(hint.get(CrossSourceStateSupport.HINT_FIELD_LEFT_KEY))
                .rightKey(hint.get(CrossSourceStateSupport.HINT_FIELD_RIGHT_KEY))
                .sourceCount(dsIds.size())
                .leftProjections(new ArrayList<>())
                .rightProjections(new ArrayList<>())
                .leftFilters(new ArrayList<>())
                .rightFilters(new ArrayList<>())
                .keyIgnoreCase(props.isJoinKeyIgnoreCase())
                .planSource(CrossSourceConstants.PLAN_SOURCE_USER_OVERRIDE)
                .build();
    }

    /** 用 LLM 补全用户指定计划的过滤/投影/聚合/表（**不得**覆盖用户指定的 join 类型与键） */
    private void applyEnrichment(CrossSourceJoinPlan plan, CrossSourceJoinPlan llmPlan) {
        if (plan == null || llmPlan == null) {
            return;
        }
        if (plan.getLeftTable() == null) {
            plan.setLeftTable(llmPlan.getLeftTable());
        }
        if (plan.getRightTable() == null) {
            plan.setRightTable(llmPlan.getRightTable());
        }
        if (plan.getLeftFilters() == null || plan.getLeftFilters().isEmpty()) {
            plan.setLeftFilters(llmPlan.getLeftFilters());
        }
        if (plan.getRightFilters() == null || plan.getRightFilters().isEmpty()) {
            plan.setRightFilters(llmPlan.getRightFilters());
        }
        if (plan.getLeftProjections() == null || plan.getLeftProjections().isEmpty()) {
            plan.setLeftProjections(llmPlan.getLeftProjections());
        }
        if (plan.getRightProjections() == null || plan.getRightProjections().isEmpty()) {
            plan.setRightProjections(llmPlan.getRightProjections());
        }
        if (plan.getLeftAggregation() == null) {
            plan.setLeftAggregation(llmPlan.getLeftAggregation());
        }
        if (plan.getRightAggregation() == null) {
            plan.setRightAggregation(llmPlan.getRightAggregation());
        }
        if (plan.getPostJoinAggregation() == null) {
            plan.setPostJoinAggregation(llmPlan.getPostJoinAggregation());
        }
        if (plan.getRawPlanText() == null) {
            plan.setRawPlanText(llmPlan.getRawPlanText());
        }
    }

    /** 用户仅指定 join 类型/左源/右源（未指定键）时，把用户字段覆盖到 LLM 计划上 */
    private void applyHintFields(CrossSourceJoinPlan plan, Map<String, String> hint, List<String> dsIds) {
        if (plan == null || hint.isEmpty()) {
            return;
        }
        CrossSourceJoinType joinType = CrossSourceJoinType
                .fromText(hint.get(CrossSourceStateSupport.HINT_FIELD_JOIN_TYPE));
        if (joinType != null) {
            plan.setJoinType(joinType);
        }
        String leftDsId = resolveDsId(hint.get(CrossSourceStateSupport.HINT_FIELD_LEFT_DS_ID), dsIds, null);
        String rightDsId = resolveDsId(hint.get(CrossSourceStateSupport.HINT_FIELD_RIGHT_DS_ID), dsIds, null);
        if (leftDsId != null) {
            plan.setLeftDsId(leftDsId);
            plan.setLeftSourceIndex(indexOf(dsIds, leftDsId));
        }
        if (rightDsId != null) {
            plan.setRightDsId(rightDsId);
            plan.setRightSourceIndex(indexOf(dsIds, rightDsId));
        }
        if (hint.containsKey(CrossSourceStateSupport.HINT_FIELD_JOIN_TYPE)
                || leftDsId != null || rightDsId != null) {
            plan.setPlanSource(CrossSourceConstants.PLAN_SOURCE_USER_OVERRIDE);
        }
    }

    /**
     * 调用规划 LLM 并解析为结构化计划。
     *
     * @return 结构化计划；LLM 异常/空输出返回 {@code null}（调用方降级单源）
     */
    private CrossSourceJoinPlan parseLlmPlan(List<String> dsIds, String question, String retrievalContext,
            String joinHint, List<SourceMetadataEvidence> evidences, CrossSourceProperties props) {
        String prompt = buildPrompt(dsIds, question, retrievalContext, joinHint, evidences);
        String output = callModel(prompt);
        if (output == null || output.isBlank()) {
            log.warn("RCAgent 跨源规划 LLM 输出为空，降级单源");
            return null;
        }
        log.info("RCAgent 跨源规划 LLM 输出长度={}", output.length());
        return CrossSourcePlanParser.parse(output, props);
    }

    /** 提示词装配（api-contract §4.2.2 逐段，顺序不可变） */
    private String buildPrompt(List<String> dsIds, String question, String retrievalContext, String joinHint,
            List<SourceMetadataEvidence> evidences) {
        StringBuilder sb = new StringBuilder();
        sb.append(skillInstruction == null ? "" : skillInstruction).append('\n');
        sb.append("【用户问题】").append(RcaNodeUtils.truncate(question, PROMPT_QUESTION_MAX_LEN)).append('\n');
        sb.append("【候选数据源（仅内部编号，输出中禁止出现 ds_id / 库名 / 表名以外的主机与连接信息）】\n");
        for (int i = 0; i < dsIds.size(); i++) {
            String evidence = evidenceText(evidences, i + 1);
            sb.append("  源 ").append(i + 1).append("：ds_id=").append(dsIds.get(i))
                    .append("；元数据：").append(evidence).append('\n');
        }
        sb.append("【用户显式指定（如有，优先级最高，必须遵守）】")
                .append(joinHint == null || joinHint.isBlank() ? "（无）" : joinHint).append('\n');
        sb.append("【检索上下文】")
                .append(retrievalContext == null || retrievalContext.isBlank()
                        ? "（无）" : RcaNodeUtils.truncate(retrievalContext, PROMPT_RETRIEVAL_MAX_LEN))
                .append('\n');
        sb.append("""
                【输出要求】
                  仅输出 JSON（无代码围栏、无解释）：{"joinType":"INNER|LEFT","leftSourceIndex":1,"rightSourceIndex":2,
                  "leftKey":"列名","rightKey":"列名","leftTable":"表名","rightTable":"表名",
                  "leftFilters":["条件"],"rightFilters":["条件"],"leftProjection":["列 AS 别名"],
                  "rightProjection":["列 AS 别名"],"leftAggregation":"可选","rightAggregation":"可选",
                  "postJoinAggregation":"可选","rationale":"一句话依据"}
                  - join key 必须来自上方元数据证据中真实存在的列；证据不足时输出 {"error":"INSUFFICIENT_EVIDENCE"}。
                  - 禁止输出 SQL 语句；禁止虚构表名/列名。
                """);
        return sb.toString();
    }

    /** 逐源证据文本（不可用标注「元数据不可用」；不阻断规划） */
    private String evidenceText(List<SourceMetadataEvidence> evidences, int sourceIndex) {
        if (evidences == null) {
            return CrossSourceConstants.EVIDENCE_UNAVAILABLE;
        }
        for (SourceMetadataEvidence evidence : evidences) {
            if (evidence != null && evidence.sourceIndex() == sourceIndex) {
                return evidence.available() && evidence.renderedText() != null
                        && !evidence.renderedText().isBlank()
                        ? evidence.renderedText() : CrossSourceConstants.EVIDENCE_UNAVAILABLE;
            }
        }
        return CrossSourceConstants.EVIDENCE_UNAVAILABLE;
    }

    /** 调用规划模型（沿用既有流式写法：StreamingChatModel 优先 stream + blockLast，异常吞掉返回 null） */
    private String callModel(String prompt) {
        if (chatModel == null) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            if (chatModel instanceof StreamingChatModel) {
                StreamingChatModel streaming = (StreamingChatModel) chatModel;
                streaming.stream(new Prompt(new UserMessage(prompt)))
                        .doOnNext(chunk -> {
                            String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                    ? chunk.getResult().getOutput().getText() : "";
                            if (text != null) {
                                sb.append(text);
                            }
                        })
                        .blockLast();
            } else {
                String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                        .getResult().getOutput().getText();
                if (result != null) {
                    sb.append(result);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RCAgent 跨源规划 LLM 调用失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    /** 补齐左右源 dsId（LLM 计划仅给内部编号，dsId 由服务端按候选源集合回填，保持内部模型完整） */
    private static void fillDsIds(CrossSourceJoinPlan plan, List<String> dsIds) {
        if (plan.getLeftDsId() == null || plan.getLeftDsId().isBlank()) {
            plan.setLeftDsId(dsIdOf(dsIds, plan.getLeftSourceIndex()));
        }
        if (plan.getRightDsId() == null || plan.getRightDsId().isBlank()) {
            plan.setRightDsId(dsIdOf(dsIds, plan.getRightSourceIndex()));
        }
    }

    private static String dsIdOf(List<String> dsIds, Integer sourceIndex) {
        if (sourceIndex == null || sourceIndex < 1 || sourceIndex > dsIds.size()) {
            return null;
        }
        return dsIds.get(sourceIndex - 1);
    }

    /** 解析用户指定 dsId（未指定时按序号兜底；序号越界返回 null） */
    private static String resolveDsId(String hintDsId, List<String> dsIds, Integer fallbackIndex) {
        if (hintDsId != null && !hintDsId.isBlank()) {
            return hintDsId.trim();
        }
        if (fallbackIndex == null || fallbackIndex < 1 || fallbackIndex > dsIds.size()) {
            return null;
        }
        return dsIds.get(fallbackIndex - 1);
    }

    private static int indexOf(List<String> dsIds, String dsId) {
        int index = dsIds.indexOf(dsId);
        return index < 0 ? 0 : index + 1;
    }

    private CrossSourceProperties effectiveProperties() {
        return properties == null ? new CrossSourceProperties() : properties;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String threadId(OverAllState state) {
        if (state == null) {
            return null;
        }
        return state.value(RcaWorkflowKeys.THREAD_ID_KEY).map(Object::toString).orElse(null);
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) {
            return;
        }
        eventBus.publish(threadId, key, value, status);
    }
}
