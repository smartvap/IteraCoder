package com.agenthub.ai.rcagent.attribution.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 指标口径冻结快照（metric_knowledge_json，BR-001）。
 *
 * <p>发起归因时冻结 kb_metric 口径（kb_metric_id/version/definition/calculation_rule/
 * related_tables/related_columns/space_name/evidence），后续下钻与结论解释引用发起时口径。</p>
 */
@Data
public class MetricKnowledgeSnapshot {

    /** 命中的 kb_metric.id（无 verified 口径为 null） */
    @JSONField(name = "kb_metric_id")
    private Long kbMetricId;

    /** 口径版本 */
    private Integer version;

    /** 指标名 */
    @JSONField(name = "metric_name")
    private String metricName;

    /** 指标定义 */
    private String definition;

    /** 计算规则/口径 */
    @JSONField(name = "calculation_rule")
    private String calculationRule;

    /** 关联表（JSON 数组，首表作为归因 SQL 主表候选） */
    @JSONField(name = "related_tables")
    private List<String> relatedTables = new ArrayList<>();

    /** 关联字段（JSON 数组，维度/时间列来源） */
    @JSONField(name = "related_columns")
    private List<String> relatedColumns = new ArrayList<>();

    /** 知识库 B 空间名 */
    @JSONField(name = "space_name")
    private String spaceName;

    /** 指标可信度 */
    private BigDecimal confidence;

    /** 是否 verified=1 且 active（0/1） */
    private Integer verified;

    /** 证据来源文本 */
    private String evidence;

    // ============ 便捷解析（避免调用处重复遍历） ============

    /** 主表（related_tables 首表），无则 null */
    public String mainTable() {
        if (relatedTables == null) {
            return null;
        }
        for (String t : relatedTables) {
            if (t != null && !t.isBlank()) {
                return t.trim();
            }
        }
        return null;
    }

    /**
     * 时间列推断：从 related_columns 匹配时间语义（create_time/stat_date/op_time 等），
     * 无匹配返回 null（由 SQL 生成器触发质量信号，提示补口径）。
     */
    public String timeColumn() {
        String col = matchColumn("create_time", "created_time", "stat_date", "stat_time", "op_time", "operate_time",
                "business_time", "time", "dt", "date");
        return col;
    }

    /**
     * 按候选列名在 related_columns 中匹配物理列（忽略大小写，"表.列"取列名末段）。
     *
     * @param candidates 候选列名（按下钻维度语义映射，如 branch_id/office_id）
     */
    public String matchColumn(String... candidates) {
        if (relatedColumns == null || relatedColumns.isEmpty()) {
            return null;
        }
        for (String c : relatedColumns) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String bare = bareColumn(c);
            if (bare == null) {
                continue;
            }
            String low = bare.toLowerCase(Locale.ROOT);
            for (String cand : candidates) {
                if (cand != null && low.equals(cand.toLowerCase(Locale.ROOT))) {
                    return bare;
                }
            }
        }
        return null;
    }

    /** related_columns 元素可能为"表.列"，返回末段列名 */
    public static String bareColumn(String columnRef) {
        if (columnRef == null || columnRef.isBlank()) {
            return null;
        }
        String s = columnRef.trim();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) {
            return s.substring(dot + 1).trim();
        }
        return s;
    }

    /** 从快照 JSON 文本解析（metric_knowledge_json 反序列化） */
    public static MetricKnowledgeSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new MetricKnowledgeSnapshot();
        }
        try {
            return JSON.parseObject(json, MetricKnowledgeSnapshot.class);
        } catch (Exception e) {
            return new MetricKnowledgeSnapshot();
        }
    }

    /** related_tables JSON 文本 → 列表（module-004 VO 字段为 JSON 文本时使用） */
    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.parseObject(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
