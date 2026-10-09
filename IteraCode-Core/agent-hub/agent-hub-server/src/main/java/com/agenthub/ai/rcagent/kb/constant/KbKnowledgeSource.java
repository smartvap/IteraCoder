package com.agenthub.ai.rcagent.kb.constant;

import java.math.BigDecimal;

/**
 * 知识来源（data-model 数据字典 KnowledgeSource）。
 *
 * <p>每类来源绑定默认可信度（BR-002）：SCHEMA=1.0 / CODE=0.95 / RUNTIME=0.8 / LLM=0.6(强制人工) / HUMAN=1.0。</p>
 */
public enum KbKnowledgeSource {

    /** 数据库字典事实（自动，可直接 verified） */
    SCHEMA("SCHEMA", new BigDecimal("1.000")),
    /** 代码/Mapper 静态解析事实（自动） */
    CODE("CODE", new BigDecimal("0.950")),
    /** 运行时观测（采集 SQL/join 证据，不自动转正） */
    RUNTIME("RUNTIME", new BigDecimal("0.800")),
    /** LLM 推断（强制人工确认，BR-002） */
    LLM("LLM", new BigDecimal("0.600")),
    /** 人工录入/确认后 */
    HUMAN("HUMAN", new BigDecimal("1.000"));

    private final String value;

    private final BigDecimal defaultConfidence;

    KbKnowledgeSource(String value, BigDecimal defaultConfidence) {
        this.value = value;
        this.defaultConfidence = defaultConfidence;
    }

    public String getValue() {
        return value;
    }

    public BigDecimal getDefaultConfidence() {
        return defaultConfidence;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbKnowledgeSource s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 来源对应的默认可信度；非法输入返回 null（由调用方兜底 0.600） */
    public static BigDecimal defaultConfidenceOf(String v) {
        for (KbKnowledgeSource s : values()) {
            if (s.value.equals(v)) {
                return s.defaultConfidence;
            }
        }
        return new BigDecimal("0.600");
    }

    /** SCHEMA 来源可自动 verified（不强制人工） */
    public static boolean isAutoVerifiedSource(String v) {
        return SCHEMA.value.equals(v) || CODE.value.equals(v);
    }
}
