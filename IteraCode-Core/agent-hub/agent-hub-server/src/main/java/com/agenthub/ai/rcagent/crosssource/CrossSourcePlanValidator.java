package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.crosssource.CrossSourceMetadataAggregator.SourceMetadataEvidence;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨源 join 计划校验器（纯函数、无状态、不抛异常）。
 *
 * <p>校验项逐条对照 {@code data-model.md §9 CS-03/CS-04} 与 api-contract §4.9：</p>
 * <ol>
 *   <li>join 类型合法（INNER / LEFT）；</li>
 *   <li>参与源恰为 2 且 ∈ {@code DS_IDS}；左右源互异；</li>
 *   <li>key 非空、≤128 且匹配 {@code ^[A-Za-z0-9_.]+$}；</li>
 *   <li>key 必须同时存在于两侧元数据列集合（大小写不敏感）；</li>
 *   <li>两侧 key 类型族可比较（数值 / 字符串 / 时间）。</li>
 * </ol>
 *
 * <p>不通过时返回具体降级原因（{@code data-model.md §5.6}），由调用方决定
 * 「LLM 计划 → 降级单源」/「用户指定计划 → 明确 FAILED」（CS-05）。</p>
 */
public final class CrossSourcePlanValidator {

    /** 类型族：数值 */
    private static final Set<String> NUMERIC_TOKENS = Set.of(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "DECIMAL", "NUMERIC",
            "NUMBER", "FLOAT", "DOUBLE", "REAL", "LONG", "SERIAL", "BIGSERIAL", "DEC", "FIXED");
    /** 类型族：时间 */
    private static final Set<String> TEMPORAL_TOKENS = Set.of(
            "DATE", "DATETIME", "TIMESTAMP", "TIME", "YEAR", "DATETIME2", "SMALLDATETIME", "INTERVAL");
    /** 类型族：字符串（含未知类型的兜底族） */
    private static final Set<String> STRING_TOKENS = Set.of(
            "CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "TEXT", "TINYTEXT",
            "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB", "STRING", "JSON", "JSONB", "ENUM", "SET",
            "UUID", "BINARY", "VARBINARY", "BLOB", "BOOLEAN", "BOOL", "BIT");

    /** 类型族：数值 */
    private static final String FAMILY_NUMERIC = "NUMERIC";
    /** 类型族：时间 */
    private static final String FAMILY_TEMPORAL = "TEMPORAL";
    /** 类型族：字符串 */
    private static final String FAMILY_STRING = "STRING";
    /** 类型族：未知 */
    private static final String FAMILY_UNKNOWN = "UNKNOWN";

    private CrossSourcePlanValidator() {
    }

    /**
     * 校验结果（不可变）。
     *
     * @param valid         是否通过
     * @param degradeReason 不通过时的具体降级原因（{@code CrossSourceConstants.DEGRADE_*}）
     */
    public record ValidationOutcome(boolean valid, String degradeReason) {

        public static ValidationOutcome ok() {
            return new ValidationOutcome(true, null);
        }

        public static ValidationOutcome fail(String degradeReason) {
            return new ValidationOutcome(false, degradeReason);
        }
    }

    /**
     * 校验计划（永不为 null 返回）。
     *
     * @param plan       待校验计划（可空 ⇒ 视为解析失败）
     * @param dsIds      候选源集合（有序，主源在首）
     * @param evidences  逐源元数据证据（可空/缺失 ⇒ 跳过该侧 key 存在性校验）
     * @param properties 跨源配置（可空取默认）
     */
    public static ValidationOutcome validate(CrossSourceJoinPlan plan, List<String> dsIds,
            List<SourceMetadataEvidence> evidences, CrossSourceProperties properties) {
        if (plan == null) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_PLAN_PARSE_FAILED);
        }
        String propertyDegradeReason = plan.getDegradeReason();
        if (propertyDegradeReason != null) {
            return ValidationOutcome.fail(propertyDegradeReason);
        }
        List<String> candidates = dsIds == null ? List.of() : dsIds;
        if (candidates.size() != 2) {
            return ValidationOutcome.fail(candidates.size() > 2
                    ? CrossSourceConstants.DEGRADE_SOURCE_COUNT_EXCEEDED
                    : CrossSourceConstants.DEGRADE_PLAN_INVALID);
        }
        if (plan.getJoinType() == null) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_PLAN_INVALID);
        }
        Integer leftIndex = plan.getLeftSourceIndex();
        Integer rightIndex = plan.getRightSourceIndex();
        if (!isValidIndex(leftIndex, candidates.size()) || !isValidIndex(rightIndex, candidates.size())) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_PLAN_INVALID);
        }
        if (leftIndex.equals(rightIndex)) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_PLAN_INVALID);
        }
        if (!isValidKey(plan.getLeftKey()) || !isValidKey(plan.getRightKey())) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_PLAN_INVALID);
        }

        SourceMetadataEvidence leftEvidence = findEvidence(evidences, leftIndex);
        SourceMetadataEvidence rightEvidence = findEvidence(evidences, rightIndex);
        boolean leftAvailable = leftEvidence != null && leftEvidence.available();
        boolean rightAvailable = rightEvidence != null && rightEvidence.available();
        if (!leftAvailable && !rightAvailable) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_METADATA_UNAVAILABLE);
        }
        String leftKey = bareName(plan.getLeftKey());
        String rightKey = bareName(plan.getRightKey());
        if (leftAvailable && isKeyMissing(leftEvidence, plan.getLeftKey(), leftKey)) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_JOIN_KEY_NOT_FOUND);
        }
        if (rightAvailable && isKeyMissing(rightEvidence, plan.getRightKey(), rightKey)) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_JOIN_KEY_NOT_FOUND);
        }
        if (leftAvailable && rightAvailable
                && !isTypeFamilyComparable(leftEvidence.columnType(leftKey), rightEvidence.columnType(rightKey))) {
            return ValidationOutcome.fail(CrossSourceConstants.DEGRADE_KEY_TYPE_INCOMPARABLE);
        }
        return ValidationOutcome.ok();
    }

    /** 计划声明的源序号是否与候选源集合一一对应 */
    public static boolean isValidIndex(Integer sourceIndex, int candidateCount) {
        return sourceIndex != null && sourceIndex >= 1 && sourceIndex <= candidateCount;
    }

    /** join 键合法性：非空、≤128、仅字母/数字/下划线/点号 */
    public static boolean isValidKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String trimmed = key.trim();
        return trimmed.length() <= CrossSourceConstants.KEY_NAME_MAX_LEN
                && CrossSourceConstants.KEY_NAME_PATTERN.matcher(trimmed).matches();
    }

    /** 剥离限定前缀（库.表.列 → 列），用于元数据列名比对 */
    public static String bareName(String name) {
        return CrossSourceConstants.stripQualifier(name);
    }

    /** 类型族可比较判定：任一侧类型未知时不做否决（避免证据缺失导致误拒） */
    static boolean isTypeFamilyComparable(String leftType, String rightType) {
        String leftFamily = typeFamily(leftType);
        String rightFamily = typeFamily(rightType);
        if (FAMILY_UNKNOWN.equals(leftFamily) || FAMILY_UNKNOWN.equals(rightFamily)) {
            return true;
        }
        return leftFamily.equals(rightFamily);
    }

    /** 类型族判定（数值 / 时间 / 字符串 / 未知） */
    static String typeFamily(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return FAMILY_UNKNOWN;
        }
        String normalized = dataType.trim().toUpperCase(Locale.ROOT);
        // 去掉精度/长度修饰与无符号标记（如 DECIMAL(10,2)、VARCHAR(64)、INT UNSIGNED）
        int parenIndex = normalized.indexOf('(');
        if (parenIndex > 0) {
            normalized = normalized.substring(0, parenIndex).trim();
        }
        normalized = normalized.replace("UNSIGNED", "").replace("ZEROFILL", "").trim();
        if (NUMERIC_TOKENS.contains(normalized)) {
            return FAMILY_NUMERIC;
        }
        if (TEMPORAL_TOKENS.contains(normalized)) {
            return FAMILY_TEMPORAL;
        }
        if (STRING_TOKENS.contains(normalized)) {
            return FAMILY_STRING;
        }
        // 未收录类型（含自定义类型）：不参与否决
        return FAMILY_UNKNOWN;
    }

    private static boolean isKeyMissing(SourceMetadataEvidence evidence, String declaredKey, String bareKey) {
        return !evidence.hasColumn(declaredKey) && !evidence.hasColumn(bareKey);
    }

    private static SourceMetadataEvidence findEvidence(List<SourceMetadataEvidence> evidences, Integer sourceIndex) {
        if (evidences == null || sourceIndex == null) {
            return null;
        }
        List<SourceMetadataEvidence> matches = new ArrayList<>();
        for (SourceMetadataEvidence evidence : evidences) {
            if (evidence != null && sourceIndex.equals(evidence.sourceIndex())) {
                matches.add(evidence);
            }
        }
        return matches.isEmpty() ? null : matches.get(0);
    }
}
