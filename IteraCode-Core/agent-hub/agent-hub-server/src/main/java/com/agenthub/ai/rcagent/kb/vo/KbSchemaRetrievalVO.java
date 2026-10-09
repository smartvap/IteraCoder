package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * Schema 检索召回响应 VO（接口20）。
 */
@Data
public class KbSchemaRetrievalVO {

    /** 表候选 */
    private List<KbTableVO> tables;

    /** 字段候选 */
    private List<KbColumnVO> columns;

    /** 索引候选：仅 include_indexes=true 时填充；未开启开关时保持 null（响应逐字段等价） */
    private List<KbIndexVO> indexes;
}
