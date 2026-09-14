package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.entity.ConversationRecord;
import com.agenthub.ai.base.service.ConversationRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/conversation")
@RequiredArgsConstructor
public class ConversationRecordController {

    private final ConversationRecordService conversationRecordService;

    /**
     * 批量保存对话消息
     */
    @PostMapping("/save")
    public Map<String, Object> saveMessages(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messagesList = (List<Map<String, Object>>) body.get("messages");
        Long userId = BaseContext.getCurrentId();

        if (sessionId == null || messagesList == null) {
            return Map.of("code", 1, "message", "sessionId and messages are required");
        }

        List<ConversationRecord> records = messagesList.stream().map(m -> {
            ConversationRecord r = new ConversationRecord();
            r.setRole((String) m.get("role"));
            r.setContent((String) m.get("content"));
            r.setModelName((String) m.get("modelName"));
            return r;
        }).toList();

        conversationRecordService.saveBatch(sessionId, userId, records);
        return Map.of("code", 0, "message", "ok");
    }

    /**
     * 按会话ID查询消息
     */
    @GetMapping("/{sessionId}")
    public List<ConversationRecord> getMessages(@PathVariable String sessionId) {
        return conversationRecordService.getBySessionId(sessionId);
    }

    /**
     * 分页查询会话列表（按 sessionId 分组，按最近更新时间倒序）
     * @param keyword 内容关键字（匹配消息内容），可选
     * @param type    chat=仅普通对话 / workflow=仅含工作流的会话 / 空=全部
     */
    @GetMapping("/sessions")
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Map<String, Object>> sessionList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type) {
        return conversationRecordService.pageSessions(page, pageSize, keyword, type);
    }

    /**
     * 删除会话消息
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> deleteMessages(@PathVariable String sessionId) {
        conversationRecordService.deleteBySessionId(sessionId);
        return Map.of("code", 0, "message", "ok");
    }
}
