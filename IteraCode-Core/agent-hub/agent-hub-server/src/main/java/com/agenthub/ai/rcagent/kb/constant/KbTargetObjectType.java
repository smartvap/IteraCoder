package com.agenthub.ai.rcagent.kb.constant;

/**
 * 审核/影响分析目标对象类型（data-model 数据字典 TargetObjectType）。
 */
public enum KbTargetObjectType {

    /** kb_table 表字典 */
    TABLE("TABLE"),
    /** kb_column 字段字典 */
    COLUMN("COLUMN"),
    /** kb_business_concept 业务概念 */
    BUSINESS_CONCEPT("BUSINESS_CONCEPT"),
    /** kb_metric 指标定义 */
    METRIC("METRIC"),
    /** kb_sql_template SQL 模板 */
    SQL_TEMPLATE("SQL_TEMPLATE"),
    /** kb_biz_link 业务链路 */
    BIZ_LINK("BIZ_LINK"),
    /** kb_relation 表关系 */
    RELATION("RELATION");

    private final String value;

    KbTargetObjectType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbTargetObjectType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 按值解析；不存在返回 null（由调用方转 42005） */
    public static KbTargetObjectType from(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        for (KbTargetObjectType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
