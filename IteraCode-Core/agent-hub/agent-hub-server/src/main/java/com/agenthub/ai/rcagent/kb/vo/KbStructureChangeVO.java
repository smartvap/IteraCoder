package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * 结构变更影响响应 VO（接口19）。
 */
@Data
public class KbStructureChangeVO {

    /** 受影响 SQL 模板数 */
    private Long impactedTemplates;

    /** 受影响业务链路数 */
    private Long impactedLinks;

    /** 受影响字段数 */
    private Long impactedColumns;

    /** 自动创建审核任务 id 列表 */
    private List<Long> reviewTaskIds;
}
