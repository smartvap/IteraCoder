package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口1 保存表知识入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbTableSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 库类型，默认 MYSQL */
    private String dbType;

    /** Schema/库名 */
    private String schemaName;

    /** 表名（必填） */
    private String tableName;

    /** 表类型 TABLE/VIEW */
    private String tableType;

    /** 表注释 */
    private String commentText;

    /** 语义标签数组 */
    private List<String> semanticTags;

    /** 采样统计（脱敏） */
    private KbSampleStats sampleStats;

    /** 知识来源，默认 SCHEMA */
    private String knowledgeSource;

    /** 置信度，不传按来源默认 */
    private BigDecimal confidence;

    /** 已验证 0/1 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态，默认 draft（SCHEMA 来源可 active） */
    private String status;

    /** 可选：级联保存列 */
    private List<KbColumnItemDTO> columns;

    /**
     * 可选：该表索引清单。
     *
     * <p>不传（null）= 完全不触碰 kb_index（既有调用方零影响）；传空数组 = 该表索引全缺失，
     * 既有非 deprecated 索引行全部软删（语义与 columns 的 droppedColumns 一致）。</p>
     */
    private List<KbIndexItemDTO> indexes;
}
