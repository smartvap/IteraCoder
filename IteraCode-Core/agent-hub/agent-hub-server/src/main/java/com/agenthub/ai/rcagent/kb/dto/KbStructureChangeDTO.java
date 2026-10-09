package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 接口19 结构变更影响入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbStructureChangeDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 受影响表名（必填，如 CUSTOMER） */
    private String affectedTable;

    /** 受影响字段（可为空） */
    private List<String> affectedColumns;

    /** 变更类型：COLUMN_ADD/COLUMN_DROP/COLUMN_TYPE/TABLE_DROP/TABLE_RENAME */
    private String changeType;

    /** 变更证据 */
    private String evidence;

    /** 是否自动为受影响对象创建审核任务，默认 true */
    private Boolean createReview;
}
