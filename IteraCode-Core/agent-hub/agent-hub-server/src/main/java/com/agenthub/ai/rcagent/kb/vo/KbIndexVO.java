package com.agenthub.ai.rcagent.kb.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Schema 索引候选 VO（接口20 响应 {@code data.indexes[]}）。
 *
 * <p>字段与 api-contract「接口 1」响应表 {@code indexes[]} 一致；对外 JSON 为 snake_case。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbIndexVO {

    private Long id;

    private String dsId;

    /** 所属表 ID（kb_table.id） */
    private Long tableId;

    private String schemaName;

    private String tableName;

    private String indexName;

    /** 索引列清单（JSON 数组文本，按索引内列顺序） */
    private String indexColumns;

    /** 是否唯一 0/1 */
    private Integer isUnique;

    /** 是否主键索引 0/1 */
    private Integer isPrimary;

    /** 索引类型（BTREE/NORMAL/…） */
    private String indexType;

    private BigDecimal confidence;

    private Integer verified;

    private String status;

    private String knowledgeSource;

    private Integer version;
}
