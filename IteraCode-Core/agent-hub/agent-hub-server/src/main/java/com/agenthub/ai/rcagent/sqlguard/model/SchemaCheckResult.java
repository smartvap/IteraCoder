package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema 预检结果（SPI S-4 出参 SchemaCheckResult）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaCheckResult {

    /** 是否全部通过 */
    private Boolean passed;

    /** 逐项校验明细 */
    @Builder.Default
    private List<SchemaCheckItem> checks = new ArrayList<>();

    /** 字典来源：KB_DICTIONARY / RUNTIME_METADATA / MIXED / UNAVAILABLE */
    private String source;
}
