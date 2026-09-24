package com.agenthub.ai.dbaccess.model;

import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * 敏感字段规则（数据字典 SensitiveFieldRule）。
 *
 * <p>{@code dsId} 为空表示全局规则（对全部数据源生效）；非空表示仅对指定数据源生效。
 * 表/列比较统一转大写（兼容存储习惯），大小写不敏感。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveFieldRule {

    /** 归属数据源标识（空 = 全局规则） */
    private String dsId;

    /** 表名 */
    private String table;

    /** 列名 */
    private String column;

    /**
     * 敏感等级（缺省 {@link SensitiveLevel#HIGH}，安全基线 fail-closed）。
     *
     * <p>register/yml/JSON 三种装载路径统一语义：显式配置 {@code LOW/MEDIUM} 可降级使用；
     * 未配置或反序列化后为 null 一律视为 HIGH（与 registerSensitive 一致，禁止因漏配 level 造成
     * DENY/MASK 静默失效）。</p>
     */
    @Builder.Default
    private SensitiveLevel level = SensitiveLevel.HIGH;

    /**
     * 生效等级：null（漏配/反序列化置空）按 HIGH 处理，杜绝消费点把 null 当「不敏感」跳过。
     */
    public SensitiveLevel effectiveLevel() {
        return level == null ? SensitiveLevel.HIGH : level;
    }

    /**
     * 是否命中给定 数据源/表/列。
     */
    public boolean matches(String targetDsId, String targetTable, String targetColumn) {
        if (dsId != null && !dsId.isBlank() && !dsId.equals(targetDsId)) {
            return false;
        }
        String t = table == null ? "" : table.trim().toUpperCase(Locale.ROOT);
        String c = column == null ? "" : column.trim().toUpperCase(Locale.ROOT);
        String targetT = targetTable == null ? "" : targetTable.trim().toUpperCase(Locale.ROOT);
        String targetC = targetColumn == null ? "" : targetColumn.trim().toUpperCase(Locale.ROOT);
        return t.equals(targetT) && c.equals(targetC);
    }

    /**
     * 从 JSON 文本解析（独立敏感清单配置读取）。
     */
    public static SensitiveFieldRule fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JSON.parseObject(json, SensitiveFieldRule.class);
    }

    @JSONField(serialize = false)
    public String toJson() {
        return JSON.toJSONString(this);
    }
}
