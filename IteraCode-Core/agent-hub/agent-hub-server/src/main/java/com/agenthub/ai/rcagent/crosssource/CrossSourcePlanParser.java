package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.engine.node.RcaNodeUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨源 join 计划解析器（纯函数、无状态、**永不抛异常**）。
 *
 * <p>把跨源规划 LLM 的结构化输出归一为 {@link CrossSourceJoinPlan}（api-contract §4.9 / §4.2.2）：
 * JSON 优先（fastjson2，与既有依赖一致）→ 去 Markdown 代码围栏 → 关键字段校验；
 * 关键字段缺失/非法 → 返回 {@code null}（由调用方降级单源）；长文本截断。
 * 解析失败**禁止**由本类抛异常（异常在内部捕获并降级为 {@code null}）。</p>
 */
public final class CrossSourcePlanParser {

    /** 计划字段：join 类型 */
    private static final String FIELD_JOIN_TYPE = "joinType";
    /** 计划字段：左源序号 */
    private static final String FIELD_LEFT_INDEX = "leftSourceIndex";
    /** 计划字段：右源序号 */
    private static final String FIELD_RIGHT_INDEX = "rightSourceIndex";
    /** 计划字段：左键 */
    private static final String FIELD_LEFT_KEY = "leftKey";
    /** 计划字段：右键 */
    private static final String FIELD_RIGHT_KEY = "rightKey";
    /** 计划字段：左表 */
    private static final String FIELD_LEFT_TABLE = "leftTable";
    /** 计划字段：右表 */
    private static final String FIELD_RIGHT_TABLE = "rightTable";
    /** 计划字段：左过滤 */
    private static final String FIELD_LEFT_FILTERS = "leftFilters";
    /** 计划字段：右过滤 */
    private static final String FIELD_RIGHT_FILTERS = "rightFilters";
    /** 计划字段：左投影 */
    private static final String FIELD_LEFT_PROJECTIONS = "leftProjection";
    /** 计划字段：右投影 */
    private static final String FIELD_RIGHT_PROJECTIONS = "rightProjection";
    /** 计划字段：左聚合 */
    private static final String FIELD_LEFT_AGG = "leftAggregation";
    /** 计划字段：右聚合 */
    private static final String FIELD_RIGHT_AGG = "rightAggregation";
    /** 计划字段：join 后聚合 */
    private static final String FIELD_POST_AGG = "postJoinAggregation";
    /** 计划字段：依据说明 */
    private static final String FIELD_RATIONALE = "rationale";
    /** 计划字段：证据不足错误标记 */
    private static final String FIELD_ERROR = "error";
    /** 证据不足标记值 */
    private static final String ERROR_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

    /** 表名/列名类字段截断长度 */
    private static final int NAME_MAX_LEN = 128;
    /** 过滤/投影单项截断长度 */
    private static final int ITEM_MAX_LEN = 200;
    /** 聚合说明截断长度 */
    private static final int AGG_MAX_LEN = 500;
    /** 依据说明截断长度 */
    private static final int RATIONALE_MAX_LEN = 500;

    private CrossSourcePlanParser() {
    }

    /**
     * 解析跨源规划 LLM 输出。
     *
     * @param llmOutput  LLM 原始输出（允许 null / 散文 / 围栏包裹 JSON）
     * @param properties 跨源配置（取 plan-max-len / join-key-ignore-case；可为 null）
     * @return 结构化计划；无法解析时返回 {@code null}（不抛异常）
     */
    public static CrossSourceJoinPlan parse(String llmOutput, CrossSourceProperties properties) {
        int planMaxLen = properties == null ? 4000 : properties.getPlanMaxLen();
        try {
            if (llmOutput == null || llmOutput.isBlank()) {
                return null;
            }
            String text = unwrapFences(llmOutput);
            String jsonText = extractJsonObject(text);
            if (jsonText == null) {
                return null;
            }
            JSONObject json;
            try {
                json = JSON.parseObject(jsonText);
            } catch (Exception e) {
                return null;
            }
            if (json == null) {
                return null;
            }
            if (json.containsKey(FIELD_ERROR)) {
                // LLM 明确回报证据不足：区别于解析失败，保留具体降级原因
                String error = str(json, FIELD_ERROR);
                String reason = error != null && error.toUpperCase().contains(ERROR_INSUFFICIENT_EVIDENCE)
                        ? CrossSourceConstants.DEGRADE_METADATA_UNAVAILABLE
                        : CrossSourceConstants.DEGRADE_PLAN_PARSE_FAILED;
                return CrossSourceJoinPlan.builder().degradeReason(reason)
                        .rawPlanText(truncate(text, planMaxLen)).build();
            }
            CrossSourceJoinType joinType = CrossSourceJoinType.fromText(str(json, FIELD_JOIN_TYPE));
            int leftIndex = json.getIntValue(FIELD_LEFT_INDEX, 0);
            int rightIndex = json.getIntValue(FIELD_RIGHT_INDEX, 0);
            String leftKey = str(json, FIELD_LEFT_KEY);
            String rightKey = str(json, FIELD_RIGHT_KEY);
            if (joinType == null || leftIndex <= 0 || rightIndex <= 0
                    || leftKey == null || rightKey == null) {
                return null;
            }
            return CrossSourceJoinPlan.builder()
                    .joinType(joinType)
                    .leftSourceIndex(leftIndex)
                    .rightSourceIndex(rightIndex)
                    .leftKey(truncate(leftKey, NAME_MAX_LEN))
                    .rightKey(truncate(rightKey, NAME_MAX_LEN))
                    .sourceCount(2)
                    .leftTable(truncate(str(json, FIELD_LEFT_TABLE), NAME_MAX_LEN))
                    .rightTable(truncate(str(json, FIELD_RIGHT_TABLE), NAME_MAX_LEN))
                    .leftProjections(strList(json, FIELD_LEFT_PROJECTIONS))
                    .rightProjections(strList(json, FIELD_RIGHT_PROJECTIONS))
                    .leftFilters(strList(json, FIELD_LEFT_FILTERS))
                    .rightFilters(strList(json, FIELD_RIGHT_FILTERS))
                    .leftAggregation(truncate(str(json, FIELD_LEFT_AGG), AGG_MAX_LEN))
                    .rightAggregation(truncate(str(json, FIELD_RIGHT_AGG), AGG_MAX_LEN))
                    .postJoinAggregation(truncate(str(json, FIELD_POST_AGG), AGG_MAX_LEN))
                    .keyIgnoreCase(properties != null && properties.isJoinKeyIgnoreCase())
                    .planSource(CrossSourceConstants.PLAN_SOURCE_LLM)
                    .rawPlanText(truncate(rationale(json), RATIONALE_MAX_LEN))
                    .build();
        } catch (Exception e) {
            // 「不抛异常」契约：解析层任何异常均降级为 null（由调用方降级单源）
            return null;
        }
    }

    /** 去除 Markdown 代码围栏（取首个围栏内容；无围栏原样返回） */
    private static String unwrapFences(String text) {
        int start = text.indexOf("```");
        if (start < 0) {
            return text;
        }
        int contentStart = text.indexOf('\n', start);
        if (contentStart < 0) {
            return text;
        }
        int end = text.indexOf("```", contentStart);
        if (end < 0) {
            return text.substring(contentStart + 1);
        }
        return text.substring(contentStart + 1, end);
    }

    /** 提取首个 JSON 对象文本（首 {@code '{'} 至末 {@code '}'}） */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 依据说明：优先 rationale，其次 leftTable/rightTable 描述缺失时的空串 */
    private static String rationale(JSONObject json) {
        return str(json, FIELD_RATIONALE);
    }

    private static String str(JSONObject json, String field) {
        Object value = json.get(field);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> strList(JSONObject json, String field) {
        List<String> result = new ArrayList<>();
        Object value = json.get(field);
        if (value == null) {
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(truncate(text, ITEM_MAX_LEN));
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            result.add(truncate(text, ITEM_MAX_LEN));
        }
        return result;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return maxLen <= 0 ? text : RcaNodeUtils.truncate(text, maxLen);
    }
}
