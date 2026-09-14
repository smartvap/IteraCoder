package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.entity.WorkflowRecord;
import com.agenthub.ai.base.service.WorkflowRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/workflow-record")
@RequiredArgsConstructor
public class WorkflowRecordController {

    private final WorkflowRecordService workflowRecordService;

    /** 保存/更新工作流记录 */
    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody WorkflowRecord record) {
        if (record.getStartTime() == null) {
            record.setStartTime(new Date());
        }
        workflowRecordService.addRecord(record);
        return Map.of("code", 0, "message", "ok");
    }

    /** 按会话ID查询工作流历史 */
    @GetMapping("/session/{sessionId}")
    public List<WorkflowRecord> getBySessionId(@PathVariable String sessionId) {
        return workflowRecordService.getBySessionId(sessionId);
    }

    /** 按线程ID查询 */
    @GetMapping("/thread/{threadId}")
    public WorkflowRecord getByThreadId(@PathVariable String threadId) {
        return workflowRecordService.getByThreadId(threadId);
    }

    /** 分页查询工作流记录列表（每个线程最新一条） */
    @GetMapping("/list")
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkflowRecord> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return workflowRecordService.pageRecords(page, pageSize, keyword, status);
    }
}
