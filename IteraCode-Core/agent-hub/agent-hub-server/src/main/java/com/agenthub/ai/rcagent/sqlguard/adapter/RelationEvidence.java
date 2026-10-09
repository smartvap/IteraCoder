package com.agenthub.ai.rcagent.sqlguard.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表关系证据（kb relation 检索候选）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationEvidence {

    /** 表 A */
    private String tableA;

    /** 表 A 列 */
    private String columnA;

    /** 表 B */
    private String tableB;

    /** 表 B 列 */
    private String columnB;

    /** 关系类型（FK/REFERENCE 等） */
    private String relationType;

    /** 置信度（kb confidence，可空） */
    private Double confidence;

    /** 是否已验证（kb verified=1） */
    private Boolean verified;
}
