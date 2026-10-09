package com.agenthub.ai.rcagent.sqlguard.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 Schema 校验结果（validate 层内部中间结构，含未知表标记供 BLOCK 判定）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class L2SchemaOutcome {

    /** 逐项校验明细（SchemaCheckItem 强类型） */
    @Builder.Default
    private List<com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckItem> checks = new ArrayList<>();

    /** 是否通过（无未知表/无缺失列/类型兼容） */
    private Boolean passed;

    /** 字典来源：KB_DICTIONARY / RUNTIME_METADATA / MIXED / UNAVAILABLE */
    private String source;

    /** 未能在字典/元数据确认的表名（未知表 → BLOCK 保守处置） */
    @Builder.Default
    private List<String> unknownTables = new ArrayList<>();

    /** 命中 HIGH 敏感字段的表.列摘要（供分级引擎） */
    @Builder.Default
    private List<String> sensitiveColumns = new ArrayList<>();
}
