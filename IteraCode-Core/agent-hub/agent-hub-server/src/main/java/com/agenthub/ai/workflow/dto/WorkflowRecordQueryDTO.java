package com.agenthub.ai.workflow.dto;

import lombok.Data;

/**
 * 工作流记录查询入参
 */
@Data
public class WorkflowRecordQueryDTO {

    /** 开始日期（含），格式 yyyy-MM-dd，可选 */
    private String startDate;

    /** 结束日期（含），格式 yyyy-MM-dd，可选 */
    private String endDate;

    /** 状态过滤：RUNNING / WAITING_REVIEW / COMPLETED / TERMINATED / FAILED，可选 */
    private String status;

    /** 研发需求模糊搜索，可选 */
    private String requirement;
}
