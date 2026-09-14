package com.agenthub.ai.base.service.impl;

import com.agenthub.ai.base.entity.ConversationRecord;
import com.agenthub.ai.base.entity.WorkflowRecord;
import com.agenthub.ai.base.mapper.ConversationRecordMapper;
import com.agenthub.ai.base.service.ConversationRecordService;
import com.agenthub.ai.base.service.WorkflowRecordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationRecordServiceImpl extends ServiceImpl<ConversationRecordMapper, ConversationRecord>
        implements ConversationRecordService {

    private final WorkflowRecordService workflowRecordService;

    @Override
    public void saveBatch(String sessionId, Long userId, List<ConversationRecord> messages) {
        Date now = new Date();
        for (ConversationRecord msg : messages) {
            msg.setSessionId(sessionId);
            msg.setUserId(userId);
            if (msg.getCreateTime() == null) {
                msg.setCreateTime(now);
            }
        }
        saveBatch(messages);
        log.info("保存对话记录: sessionId={}, count={}", sessionId, messages.size());
    }

    @Override
    public List<ConversationRecord> getBySessionId(String sessionId) {
        return list(new LambdaQueryWrapper<ConversationRecord>()
                .eq(ConversationRecord::getSessionId, sessionId)
                .orderByAsc(ConversationRecord::getCreateTime));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        remove(new LambdaQueryWrapper<ConversationRecord>()
                .eq(ConversationRecord::getSessionId, sessionId));
        log.info("删除对话记录: sessionId={}", sessionId);
    }

    @Override
    public Page<Map<String, Object>> pageSessions(int page, int pageSize, String keyword, String type) {
        QueryWrapper<ConversationRecord> qw = new QueryWrapper<>();
        qw.select("session_id AS sessionId",
                "COUNT(*) AS messageCount",
                "MAX(create_time) AS lastTime",
                "MIN(create_time) AS firstTime");

        // 关键字搜索：仅返回含匹配消息的会话
        if (keyword != null && !keyword.isBlank()) {
            qw.like("content", keyword.trim());
        }

        // 类型筛选：workflow 时仅返回含工作流记录的会话
        if ("workflow".equalsIgnoreCase(type)) {
            List<String> wfSessionIds = workflowRecordService.list(
                            new QueryWrapper<WorkflowRecord>().select("DISTINCT session_id"))
                    .stream()
                    .map(WorkflowRecord::getSessionId)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
            if (wfSessionIds.isEmpty()) {
                Page<Map<String, Object>> empty = new Page<>(page, pageSize);
                empty.setRecords(java.util.Collections.emptyList());
                empty.setTotal(0);
                return empty;
            }
            qw.in("session_id", wfSessionIds);
        } else if ("chat".equalsIgnoreCase(type)) {
            // chat 时排除含工作流的会话
            List<String> wfSessionIds = workflowRecordService.list(
                            new QueryWrapper<WorkflowRecord>().select("DISTINCT session_id"))
                    .stream()
                    .map(WorkflowRecord::getSessionId)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
            if (!wfSessionIds.isEmpty()) {
                qw.notIn("session_id", wfSessionIds);
            }
        }

        qw.groupBy("session_id").orderByDesc("lastTime");

        Page<Map<String, Object>> result = new Page<>(page, pageSize);
        baseMapper.selectMapsPage(result, qw);

        // 为每个会话补充标题、预览以及关联的工作流信息
        for (Map<String, Object> row : result.getRecords()) {
            String sessionId = String.valueOf(row.get("sessionId"));
            List<ConversationRecord> msgs = getBySessionId(sessionId);
            if (!msgs.isEmpty()) {
                ConversationRecord first = msgs.get(0);
                String title = "user".equals(first.getRole()) ? first.getContent() : msgs.stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .map(ConversationRecord::getContent)
                        .findFirst().orElse("对话");
                row.put("title", truncate(title, 60));
                ConversationRecord last = msgs.get(msgs.size() - 1);
                row.put("preview", truncate(last.getContent(), 100));
            } else {
                row.put("title", "对话");
                row.put("preview", "");
            }
            // 关联工作流信息
            List<WorkflowRecord> wfList = workflowRecordService.getBySessionId(sessionId);
            if (!wfList.isEmpty()) {
                WorkflowRecord latest = wfList.get(wfList.size() - 1);
                row.put("hasWorkflow", true);
                row.put("workflowStatus", latest.getStatus());
                row.put("workflowThreadId", latest.getThreadId());
                row.put("workflowRounds", wfList.size());
                row.put("workflowRequirement", truncate(latest.getRequirement(), 100));
            } else {
                row.put("hasWorkflow", false);
            }
        }
        return result;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
