package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 对话记录实体。
 *
 * <p><b>2026-08-13 新增</b>：用于将所有对话（普通对话 + 工作流）持久化到 MySQL，
 * 支持按会话查询、删除，实现对话历史追溯。与 {@code conversation_record} 表对应。</p>
 *
 * @author AgentHub
 * @since 2026-08-13
 */
@TableName(value = "conversation_record")
@Data
public class ConversationRecord {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID（关联前后端会话，普通对话用 sessionStore 的 id，工作流用共享 session id） */
    private String sessionId;

    /** 用户ID（匿名时为 null） */
    private Long userId;

    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 模型名称 */
    private String modelName;

    /** 创建时间 */
    private Date createTime;
}
