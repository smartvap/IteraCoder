package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 接口8 Schema 批量同步入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbSchemaBatchDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 表数组（每项=表保存入参 + columns 子数组），非空 */
    private List<KbTableSaveDTO> tables;

    /** true=全量（缺失表置 deprecated 提示）；false=增量 */
    private Boolean fullSync;
}
