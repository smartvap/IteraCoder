package com.agenthub.ai.base.service;

import com.agenthub.ai.base.entity.ConversationRecord;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

public interface ConversationRecordService extends IService<ConversationRecord> {
    /**
     * 批量保存对话消息
     * @param sessionId 会话ID
     * @param messages  消息列表
     */
    void saveBatch(String sessionId, Long userId, List<ConversationRecord> messages);

    /**
     * 按会话ID查询消息
     */
    List<ConversationRecord> getBySessionId(String sessionId);

    /**
     * 删除会话的所有消息
     */
    void deleteBySessionId(String sessionId);

    /**
     * 分页查询会话列表（按 sessionId 分组，按最近更新时间倒序）
     *
     * @param keyword 内容关键字（匹配消息内容），可选
     * @param type    chat=仅普通对话 / workflow=仅含工作流的会话 / 空=全部
     */
    Page<Map<String, Object>> pageSessions(int page, int pageSize, String keyword, String type);
}
