package com.agenthub.ai.base.service;

import com.agenthub.ai.base.entity.WorkflowRecord;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface WorkflowRecordService extends IService<WorkflowRecord> {
    /** 保存工作流记录 */
    void addRecord(WorkflowRecord record);

    /** 按会话ID查询所有轮次 */
    List<WorkflowRecord> getBySessionId(String sessionId);

    /** 按线程ID查询 */
    WorkflowRecord getByThreadId(String threadId);

    /**
     * 分页查询工作流记录（按最新轮次）
     *
     * @param keyword 需求/内容关键字，可选
     * @param status  状态筛选，可选
     */
    Page<WorkflowRecord> pageRecords(int page, int pageSize, String keyword, String status);
}
