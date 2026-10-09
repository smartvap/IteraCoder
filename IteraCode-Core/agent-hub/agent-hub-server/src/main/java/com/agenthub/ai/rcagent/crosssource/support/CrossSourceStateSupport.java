package com.agenthub.ai.rcagent.crosssource.support;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.crosssource.CrossSourceConstants;
import com.agenthub.ai.rcagent.crosssource.CrossSourceJoinType;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨源 State 判定与 JOIN_HINT 装配工具（module-003，静态无状态）。
 *
 * <p>职责：① {@code DS_IDS} 多形态读取（{@code List} / 数组 / JSON 字符串）；
 * ② 跨源分支判定（{@code QUERY} 意图 && 源数 &gt; 1 && enabled）；③ {@code recover} 续跑判定；
 * ④ {@code JOIN_HINT} 的装配与解析（用户显式指定 join 的兜底入口）。</p>
 *
 * <p><b>不修改</b> {@link RcaWorkflowKeys} 既有方法（仅读取其常量）；纯函数、可并发、不抛业务异常
 * （仅 {@link #normalizeAndValidateJoinHint} 按参数校验契约抛 40000 中文提示）。</p>
 */
public final class CrossSourceStateSupport {

    /** JOIN_HINT 键值分隔符 */
    private static final String HINT_KV_SEP = "=";
    /** JOIN_HINT 项分隔符 */
    private static final String HINT_ITEM_SEP = ";";

    /** JOIN_HINT：join 类型项名 */
    public static final String HINT_FIELD_JOIN_TYPE = "joinType";
    /** JOIN_HINT：左源项名 */
    public static final String HINT_FIELD_LEFT_DS_ID = "leftDsId";
    /** JOIN_HINT：右源项名 */
    public static final String HINT_FIELD_RIGHT_DS_ID = "rightDsId";
    /** JOIN_HINT：左键项名 */
    public static final String HINT_FIELD_LEFT_KEY = "leftJoinKey";
    /** JOIN_HINT：右键项名 */
    public static final String HINT_FIELD_RIGHT_KEY = "rightJoinKey";

    /** 意图：查数 */
    private static final String INTENT_QUERY = "QUERY";
    /** 候选源 ID 长度上限 */
    private static final int MAX_DS_ID_LEN = 64;

    private CrossSourceStateSupport() {
    }

    /**
     * 读取候选源集合（兼容 {@code List} / 数组 / JSON 或逗号字符串三种序列化形态）。
     * 缺失或为空返回空列表（不回落 {@code DS_ID}，由调用方决定回退策略）。
     */
    public static List<String> dsIds(OverAllState state) {
        List<String> ids = new ArrayList<>();
        if (state == null) {
            return ids;
        }
        Object value = state.value(RcaWorkflowKeys.DS_IDS).orElse(null);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addIfPresent(ids, item);
            }
        } else if (value instanceof Object[] array) {
            for (Object item : array) {
                addIfPresent(ids, item);
            }
        } else if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                try {
                    List<String> parsed = JSON.parseArray(trimmed, String.class);
                    if (parsed != null) {
                        for (String item : parsed) {
                            addIfPresent(ids, item);
                        }
                        return ids;
                    }
                } catch (Exception ignored) {
                    // 非 JSON 数组形态：退化为逗号切分（与既有 checkpoint 兼容策略一致）
                }
                if (trimmed.endsWith("]")) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
            }
            for (String part : trimmed.split(",")) {
                addIfPresent(ids, part.replace("\"", ""));
            }
        }
        return ids;
    }

    /** 候选源数量（{@code DS_IDS} 元素数） */
    public static int dsIdCount(OverAllState state) {
        return dsIds(state).size();
    }

    /**
     * 是否进入跨源分支：仅 {@code QUERY} 意图 + 候选源数 &gt; 1 + {@code enabled=true} 三者同时成立。
     * 其余（CHAT / ATTRIBUTION / 单源 / enabled=false）一律走既有链路（零回归）。
     */
    public static boolean isCrossSourceQuery(OverAllState state, CrossSourceProperties properties) {
        if (state == null) {
            return false;
        }
        if (properties != null && !properties.isEnabled()) {
            return false;
        }
        String intent = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, "");
        return INTENT_QUERY.equals(intent) && dsIdCount(state) > 1;
    }

    /**
     * 是否为跨源续跑场景（{@code recover} 续跑位置判定前置条件）：
     * 候选源数 &gt; 1 **且** 至少存在一个跨源内部键（JOIN_PLAN / SOURCE_QUERY_PLANS / SOURCE_QUERY_RESULTS）。
     * 单源会话恒不成立 ⇒ 既有判定顺序与结果不变。
     */
    public static boolean isCrossSourceResume(OverAllState state) {
        if (state == null || dsIdCount(state) <= 1) {
            return false;
        }
        return hasText(state, RcaWorkflowKeys.JOIN_PLAN)
                || hasText(state, RcaWorkflowKeys.SOURCE_QUERY_PLANS)
                || hasText(state, RcaWorkflowKeys.SOURCE_QUERY_RESULTS);
    }

    /** State 键是否非空白文本 */
    public static boolean hasText(OverAllState state, String key) {
        if (state == null || key == null) {
            return false;
        }
        return !RcaWorkflowKeys.extractStateText(state, key, "").isBlank();
    }

    /** 装配 JOIN_HINT（仅拼接非空项，固定顺序；无任何非空项返回空串 ⇒ 不写该键） */
    public static String buildJoinHint(String joinType, String leftDsId, String rightDsId,
            String leftJoinKey, String rightJoinKey) {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, HINT_FIELD_JOIN_TYPE, joinType);
        putIfPresent(fields, HINT_FIELD_LEFT_DS_ID, leftDsId);
        putIfPresent(fields, HINT_FIELD_RIGHT_DS_ID, rightDsId);
        putIfPresent(fields, HINT_FIELD_LEFT_KEY, leftJoinKey);
        putIfPresent(fields, HINT_FIELD_RIGHT_KEY, rightJoinKey);
        if (fields.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            items.add(entry.getKey() + HINT_KV_SEP + entry.getValue());
        }
        return String.join(HINT_ITEM_SEP, items);
    }

    /** 解析 JOIN_HINT 文本为项映射（非法项忽略；空/空白返回空映射，永不抛异常） */
    public static Map<String, String> parseJoinHint(String hint) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (hint == null || hint.isBlank()) {
            return fields;
        }
        for (String item : hint.split(HINT_ITEM_SEP)) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            int idx = trimmed.indexOf(HINT_KV_SEP);
            if (idx <= 0 || idx == trimmed.length() - 1) {
                continue;
            }
            String name = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                fields.put(name, value);
            }
        }
        return fields;
    }

    /**
     * 跨源字段的手动校验 + 归一化并装配 JOIN_HINT（api-contract §2.2 逐条）。
     *
     * <p>校验失败一律 {@link BusinessException}（40000 + 中文提示）；五个字段全空时返回空串
     * （调用方据此**不写** {@code JOIN_HINT}，保证单源零回归）。</p>
     *
     * @param joinType        用户指定 join 类型（可空）
     * @param leftDsId        用户指定左源（可空）
     * @param rightDsId       用户指定右源（可空）
     * @param leftJoinKey     用户指定左键（可空）
     * @param rightJoinKey    用户指定右键（可空）
     * @param candidateDsIds  服务端解析后的候选源集合（主源在首）
     * @param maxSources      配置的源数上限
     * @return 归一化后的 JOIN_HINT 文本；全空返回空串
     */
    public static String normalizeAndValidateJoinHint(String joinType, String leftDsId, String rightDsId,
            String leftJoinKey, String rightJoinKey, List<String> candidateDsIds, int maxSources) {
        if (isBlank(joinType) && isBlank(leftDsId) && isBlank(rightDsId)
                && isBlank(leftJoinKey) && isBlank(rightJoinKey)) {
            return "";
        }
        List<String> candidates = candidateDsIds == null ? List.of() : candidateDsIds;
        int size = candidates.size();
        if (maxSources > 0 && size > maxSources) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, CrossSourceConstants.NOTE_SOURCE_COUNT_EXCEEDED);
        }
        if (size != 2) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "跨源关联需要且仅需要 2 个数据源，请检查查询范围");
        }

        String normalizedType = null;
        if (!isBlank(joinType)) {
            CrossSourceJoinType type = CrossSourceJoinType.fromText(joinType);
            if (type == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "join 类型仅支持 INNER / LEFT");
            }
            normalizedType = type.name();
        }

        String left = checkDsId(leftDsId, candidates);
        String right = checkDsId(rightDsId, candidates);
        if (left != null && right != null && left.equals(right)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "join 左右数据源不能相同");
        }

        boolean leftKeyProvided = !isBlank(leftJoinKey);
        boolean rightKeyProvided = !isBlank(rightJoinKey);
        if (leftKeyProvided != rightKeyProvided) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "join 键需左右同时指定");
        }
        String leftKey = null;
        String rightKey = null;
        if (leftKeyProvided) {
            leftKey = checkKey(leftJoinKey);
            rightKey = checkKey(rightJoinKey);
        }
        return buildJoinHint(normalizedType, left, right, leftKey, rightKey);
    }

    /** dsId 校验：≤64 且必须 ∈ 候选源集合（空白返回 null） */
    private static String checkDsId(String dsId, List<String> candidates) {
        if (isBlank(dsId)) {
            return null;
        }
        String trimmed = dsId.trim();
        if (trimmed.length() > MAX_DS_ID_LEN) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据源 ID 过长");
        }
        if (!candidates.contains(trimmed)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "指定的 join 数据源必须在候选源集合内");
        }
        return trimmed;
    }

    /** join 键校验：≤128 且仅字母/数字/下划线/点号 */
    private static String checkKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.length() > CrossSourceConstants.KEY_NAME_MAX_LEN
                || !CrossSourceConstants.KEY_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "join 键仅允许字母/数字/下划线/点号，且不超过 128 字符");
        }
        return trimmed;
    }

    private static void addIfPresent(List<String> target, Object item) {
        if (item == null) {
            return;
        }
        String text = String.valueOf(item).trim();
        if (!text.isEmpty() && !target.contains(text)) {
            target.add(text);
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value.trim());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
