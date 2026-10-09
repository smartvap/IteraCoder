package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Schema 校验项（SchemaCheckResult.checks）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaCheckItem {

    /** 校验类型：TABLE / COLUMN / JOIN */
    private String type;

    /** 校验目标描述（表名 / 表.列 / A.col=B.col） */
    private String target;

    /** 是否存在/已知 */
    private Boolean exists;

    /** 类型是否可比较（JOIN 两侧或列声明，未知可为 true 并备注） */
    private Boolean typeMatch;

    /** 证据摘要（来源 kb 字典 / 运行时元数据，不含数据字面量） */
    private String evidence;

    /** 校验原因/失败说明 */
    private String reason;
}
