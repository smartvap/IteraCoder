package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Token使用量明细
 * @TableName token_usage_detail
 */
@TableName(value = "token_usage_detail")
@Data
public class TokenUsageDetail {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * IP地址
     */
    private String ipAddress;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 提示词token数
     */
    private Integer promptTokens;

    /**
     * 完成token数
     */
    private Integer completionTokens;

    /**
     * 总耗时(毫秒)
     */
    private Long totalDurationMs;

    /**
     * 请求时间
     */
    private Date requestTime;

    /**
     * 状态 0:等待 1:完成 2:失败
     */
    private Integer status;
}
