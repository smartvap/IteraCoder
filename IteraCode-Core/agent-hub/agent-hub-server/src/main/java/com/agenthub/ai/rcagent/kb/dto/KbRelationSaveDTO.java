package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口3 保存表关系入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbRelationSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 库类型 */
    private String dbType;

    /** 表 A（必填） */
    private String tableA;

    /** 字段 A */
    private String columnA;

    /** 表 B（必填） */
    private String tableB;

    /** 字段 B */
    private String columnB;

    /** join 字段映射数组（如 [["ACCOUNT.CUST_ID","CUSTOMER.ID"]]） */
    private List<List<String>> joinFields;

    /** 关系类型 FK/REFERENCE（必填） */
    private String relationType;

    /** 关系来源 FK/CODE/RUNTIME_JOIN */
    private String relationSource;

    /** 统一知识来源 */
    private String knowledgeSource;

    /** 置信度（按来源默认） */
    private BigDecimal confidence;

    /** 已验证 0/1 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态，默认 draft */
    private String status;
}
