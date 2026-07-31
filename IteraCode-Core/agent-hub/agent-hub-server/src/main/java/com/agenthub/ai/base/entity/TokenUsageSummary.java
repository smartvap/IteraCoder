package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Token使用量汇总
 * @TableName token_usage_summary
 */
@TableName(value = "token_usage_summary")
@Data
public class TokenUsageSummary {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 统计类型（daily/weekly/monthly/total）
     */
    private String statType;

    /**
     * 统计维度标识（用户ID/IP地址）
     */
    private String statKey;

    /**
     * 统计日期
     */
    private Date statDate;

    /**
     * 总请求次数
     */
    private Integer totalRequests;

    /**
     * 总提示词token数
     */
    private Long totalPromptTokens;

    /**
     * 总完成token数
     */
    private Long totalCompletionTokens;

    /**
     * 总耗时(毫秒)
     */
    private Long totalDurationMs;
}
