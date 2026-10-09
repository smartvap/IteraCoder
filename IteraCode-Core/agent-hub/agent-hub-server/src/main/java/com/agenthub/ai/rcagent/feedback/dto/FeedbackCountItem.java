package com.agenthub.ai.rcagent.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分组统计行（GROUP BY 返回 key + cnt，供 /stats 聚合）。
 * 使用强类型行避免弱类型 Map 散落 Service。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackCountItem {

    /** 分组键（feedback_type / status / wiki status 等） */
    private String groupKey;

    /** 数量 */
    private Long cnt;
}
