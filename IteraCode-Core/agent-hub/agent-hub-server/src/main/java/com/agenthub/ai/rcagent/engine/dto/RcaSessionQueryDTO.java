package com.agenthub.ai.rcagent.engine.dto;

import lombok.Data;

/**
 * RCAgent 会话历史分页查询条件（GET /api/v1/rcagent/sessions，参数均可选）。
 */
@Data
public class RcaSessionQueryDTO {

    /** 状态筛选（RUNNING/WAITING_REVIEW/SENT_BACK/COMPLETED/TERMINATED/FAILED） */
    private String status;

    /** 数据源筛选 */
    private String dsId;

    /** 问题模糊搜索 */
    private String question;

    /** 精确会话 ID */
    private String threadId;

    /** 起始日期（含），格式 yyyy-MM-dd */
    private String startDate;

    /** 结束日期（含），格式 yyyy-MM-dd */
    private String endDate;

    /** 页码，默认 1 */
    private Integer page = 1;

    /** 页大小，默认 15 */
    private Integer pageSize = 15;
}
