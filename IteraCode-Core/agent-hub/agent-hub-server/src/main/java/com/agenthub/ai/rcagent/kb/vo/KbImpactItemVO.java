package com.agenthub.ai.rcagent.kb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 影响对象摘要项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbImpactItemVO {

    /** 影响对象类型，如 SQL_TEMPLATE / BIZ_LINK / COLUMN */
    private String type;

    /** 影响对象 id */
    private Long id;

    /** 影响对象名称/描述 */
    private String name;
}
