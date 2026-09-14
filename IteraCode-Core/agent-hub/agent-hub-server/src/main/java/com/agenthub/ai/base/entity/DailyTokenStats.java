package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@TableName(value = "daily_token_stats")
@Data
public class DailyTokenStats {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Date statDate;
    private Integer totalRequests;
    private Long totalPromptTokens;
    private Long totalCompletionTokens;
    private Long totalDurationMs;
    private Integer totalUsers;
    private Date createTime;
    private Date updateTime;
}
