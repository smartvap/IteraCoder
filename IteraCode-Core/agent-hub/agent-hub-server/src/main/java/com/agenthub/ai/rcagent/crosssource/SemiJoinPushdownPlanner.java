package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.PushdownPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 半连接下推规划器（纯计算、无 IO、无状态，module-003）。
 *
 * <p>契约（api-contract §4.9 / {@code data-model.md §5.3/§5.4}）：</p>
 * <ol>
 *   <li>key 去重 + 归一化（字符串 trim、数值 BigDecimal 归一、时间 → epoch 毫秒 / ISO 文本）；</li>
 *   <li><b>字面量安全渲染</b>：仅数字 / 日期时间 / {@code ^[A-Za-z0-9_\-.@:]{1,64}$} 字符串可拼接，
 *       其余（含引号/分号/注释符/空白/超长/NULL）**一律剔除并计数**，绝不拼接（防 SQL 注入）；</li>
 *   <li>{@code size ≤ semi-join-in-threshold} ⇒ {@code SINGLE_IN}（1 片）；</li>
 *   <li>超出 ⇒ 按阈值分片；片数 &gt; {@code semi-join-max-shards} ⇒ {@code NONE}（回退）并标注；</li>
 *   <li>开关关闭 ⇒ {@code NONE} 并标注。</li>
 * </ol>
 */
@Slf4j
@Component
public class SemiJoinPushdownPlanner {

    /** 分片备注前缀（不含数量） */
    private static final int MAX_NOTE_LEN = 200;

    /**
     * 规划下推。
     *
     * @param probeKeys       探测侧 key 集合（可为 null）
     * @param targetKeyColumn 目标侧 join 键列（计划给出的原文，可为空）
     * @param properties      跨源配置（可空取安全默认：关闭下推 → NONE）
     * @return 下推计划（永不为 null）
     */
    public PushdownPlan plan(List<Object> probeKeys, String targetKeyColumn, CrossSourceProperties properties) {
        boolean enabled = properties != null && properties.isSemiJoinPushdownEnabled();
        int threshold = properties == null ? 1000 : Math.max(1, properties.getSemiJoinInThreshold());
        int maxShards = properties == null ? 10 : Math.max(1, properties.getSemiJoinMaxShards());
        int literalMaxLen = properties == null ? 64 : Math.max(1, properties.getKeyLiteralMaxLen());
        String column = targetKeyColumn == null ? "" : targetKeyColumn.trim();

        if (!enabled) {
            log.info("RCAgent 半连接下推: 已关闭，回退不下推");
            return base(PushdownPlan.builder()
                    .pushdownMode(CrossSourceConstants.PUSHDOWN_NONE)
                    .targetKeyColumn(column)
                    .note(CrossSourceConstants.PUSHDOWN_NOTE_DISABLED)
                    .build());
        }

        Set<String> literals = new LinkedHashSet<>();
        int unsafeCount = 0;
        int distinctKeys = 0;
        Set<String> seenKeys = new LinkedHashSet<>();
        if (probeKeys != null) {
            for (Object key : probeKeys) {
                String normalized = normalizeKeyToken(key, properties);
                if (normalized == null) {
                    continue;
                }
                if (!seenKeys.add(normalized)) {
                    continue;
                }
                distinctKeys++;
                String literal = renderLiteral(key, literalMaxLen);
                if (literal == null) {
                    unsafeCount++;
                } else {
                    literals.add(literal);
                }
            }
        }
        int keyCount = distinctKeys;
        if (literals.isEmpty()) {
            String note = unsafeCount > 0
                    ? unsafeCount + CrossSourceConstants.PUSHDOWN_NOTE_UNSAFE_PREFIX
                    : CrossSourceConstants.PUSHDOWN_NOTE_NO_SAFE_KEY;
            return base(PushdownPlan.builder()
                    .pushdownMode(CrossSourceConstants.PUSHDOWN_NONE)
                    .targetKeyColumn(column)
                    .keyCount(keyCount)
                    .unsafeKeyCount(unsafeCount)
                    .note(note)
                    .build());
        }

        List<String> ordered = new ArrayList<>(literals);
        List<List<String>> chunks = new ArrayList<>();
        if (ordered.size() <= threshold) {
            chunks.add(ordered);
        } else {
            for (int start = 0; start < ordered.size(); start += threshold) {
                int end = Math.min(start + threshold, ordered.size());
                chunks.add(new ArrayList<>(ordered.subList(start, end)));
            }
        }
        if (chunks.size() > maxShards) {
            log.info("RCAgent 半连接下推: keyCount={}, shardCount={} 超上限 {}，回退不下推",
                    keyCount, chunks.size(), maxShards);
            return base(PushdownPlan.builder()
                    .pushdownMode(CrossSourceConstants.PUSHDOWN_NONE)
                    .targetKeyColumn(column)
                    .keyCount(keyCount)
                    .unsafeKeyCount(unsafeCount)
                    .note(combineNote(CrossSourceConstants.PUSHDOWN_NOTE_TOO_MANY_SHARDS, unsafeCount))
                    .build());
        }
        String mode = chunks.size() == 1
                ? CrossSourceConstants.PUSHDOWN_SINGLE_IN : CrossSourceConstants.PUSHDOWN_SHARDED_IN;
        log.info("RCAgent 半连接下推: mode={}, keyCount={}, unsafeKeyCount={}, shardCount={}",
                mode, keyCount, unsafeCount, chunks.size());
        return base(PushdownPlan.builder()
                .pushdownMode(mode)
                .targetKeyColumn(column)
                .keyCount(keyCount)
                .unsafeKeyCount(unsafeCount)
                .chunks(chunks)
                .shardCount(chunks.size())
                .note(unsafeCount > 0 ? unsafeCount + CrossSourceConstants.PUSHDOWN_NOTE_UNSAFE_PREFIX : null)
                .build());
    }

    /** 备注拼接（回退原因 + 剔除计数），长度截断 */
    private static String combineNote(String reason, int unsafeCount) {
        String note = unsafeCount > 0
                ? reason + "；" + unsafeCount + CrossSourceConstants.PUSHDOWN_NOTE_UNSAFE_PREFIX
                : reason;
        return CrossSourceConstants.truncate(note, MAX_NOTE_LEN);
    }

    private static PushdownPlan base(PushdownPlan plan) {
        if (plan.getChunks() == null) {
            plan.setChunks(new ArrayList<>());
        }
        plan.setShardCount(plan.getChunks().size());
        return plan;
    }

    /**
     * key 归一化（data-model §5.4）：字符串 trim（可选忽略大小写）、数值 BigDecimal 归一、
     * 时间 → epoch 毫秒 / ISO 文本；空白/NULL 不参与（返回 null）。
     */
    public static String normalizeKeyToken(Object key, CrossSourceProperties properties) {
        boolean ignoreCase = properties != null && properties.isJoinKeyIgnoreCase();
        if (key == null) {
            return null;
        }
        if (key instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return ignoreCase ? trimmed.toLowerCase(java.util.Locale.ROOT) : trimmed;
        }
        if (key instanceof Number number) {
            return toBigDecimal(number).stripTrailingZeros().toPlainString();
        }
        if (key instanceof Date date) {
            return String.valueOf(date.getTime());
        }
        if (key instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        if (key instanceof LocalDate date) {
            return date.toString();
        }
        String text = String.valueOf(key).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 字面量安全渲染（data-model §5.3）：不安全的返回值一律 {@code null}（**绝不拼接**）。
     */
    static String renderLiteral(Object key, int literalMaxLen) {
        if (key == null) {
            return null;
        }
        if (key instanceof Byte || key instanceof Short || key instanceof Integer || key instanceof Long
                || key instanceof java.math.BigInteger) {
            return String.valueOf(key);
        }
        if (key instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (key instanceof Double || key instanceof Float) {
            return toBigDecimal((Number) key).stripTrailingZeros().toPlainString();
        }
        if (key instanceof Date date) {
            return String.valueOf(date.getTime());
        }
        if (key instanceof LocalDateTime dateTime) {
            return quoteIfSafe(dateTime.toString(), literalMaxLen, false);
        }
        if (key instanceof LocalDate date) {
            return quoteIfSafe(date.toString(), literalMaxLen, false);
        }
        if (key instanceof String text) {
            return quoteIfSafe(text.trim(), literalMaxLen, true);
        }
        // 其余类型（布尔/集合/对象等）：一律剔除，不拼接
        return null;
    }

    /** 字符串字面量渲染：合法则加单引号包裹，否则剔除 */
    private static String quoteIfSafe(String value, int literalMaxLen, boolean requirePattern) {
        if (value == null || value.isEmpty() || value.length() > literalMaxLen) {
            return null;
        }
        boolean ok = CrossSourceConstants.KEY_LITERAL_PATTERN.matcher(value).matches()
                || CrossSourceConstants.KEY_DATETIME_PATTERN.matcher(value).matches();
        if (!ok) {
            return null;
        }
        if (requirePattern && value.contains("'")) {
            return null;
        }
        return "'" + value + "'";
    }

    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
