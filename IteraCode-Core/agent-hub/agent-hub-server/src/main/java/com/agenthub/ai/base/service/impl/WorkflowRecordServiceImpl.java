package com.agenthub.ai.base.service.impl;

import com.agenthub.ai.base.entity.WorkflowRecord;
import com.agenthub.ai.base.mapper.WorkflowRecordMapper;
import com.agenthub.ai.base.service.WorkflowRecordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WorkflowRecordServiceImpl extends ServiceImpl<WorkflowRecordMapper, WorkflowRecord>
        implements WorkflowRecordService {

    @Override
    public void addRecord(WorkflowRecord record) {
        if (record.getCreateTime() == null) {
            record.setCreateTime(new Date());
        }
        baseMapper.insert(record);
        log.info("保存工作流记录: threadId={}, round={}, status={}",
                record.getThreadId(), record.getRound(), record.getStatus());
    }

    @Override
    public List<WorkflowRecord> getBySessionId(String sessionId) {
        return list(new LambdaQueryWrapper<WorkflowRecord>()
                .eq(WorkflowRecord::getSessionId, sessionId)
                .orderByAsc(WorkflowRecord::getRound)
                .orderByAsc(WorkflowRecord::getCreateTime));
    }

    @Override
    public WorkflowRecord getByThreadId(String threadId) {
        // 同一 threadId 可能有多轮记录，取最新一条（多轮 SENT_BACK 会插多条）
        return getOne(new LambdaQueryWrapper<WorkflowRecord>()
                .eq(WorkflowRecord::getThreadId, threadId)
                .orderByDesc(WorkflowRecord::getCreateTime)
                .last("LIMIT 1"));
    }

    @Override
    public Page<WorkflowRecord> pageRecords(int page, int pageSize, String keyword, String status) {
        LambdaQueryWrapper<WorkflowRecord> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(WorkflowRecord::getRequirement, keyword.trim())
                    .or().like(WorkflowRecord::getWorkflowMessage, keyword.trim()));
        }
        if (StringUtils.hasText(status)) {
            qw.eq(WorkflowRecord::getStatus, status.trim());
        }
        qw.orderByDesc(WorkflowRecord::getCreateTime);

        Page<WorkflowRecord> allPage = new Page<>(1, Integer.MAX_VALUE);
        Page<WorkflowRecord> all = page(allPage, qw);
        // 每个 threadId 保留最新一条（首条即最新，因已按 createTime 倒序）
        Map<String, WorkflowRecord> latest = new LinkedHashMap<>();
        for (WorkflowRecord r : all.getRecords()) {
            latest.putIfAbsent(r.getThreadId(), r);
        }
        List<WorkflowRecord> dedup = new java.util.ArrayList<>(latest.values());

        // 手动分页
        int total = dedup.size();
        int from = Math.min((page - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<WorkflowRecord> records = dedup.subList(from, to);

        Page<WorkflowRecord> result = new Page<>(page, pageSize, total);
        result.setRecords(records);
        return result;
    }
}
