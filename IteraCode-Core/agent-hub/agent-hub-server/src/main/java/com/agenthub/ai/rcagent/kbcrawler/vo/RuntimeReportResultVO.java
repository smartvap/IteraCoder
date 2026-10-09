package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

/**
 * 接口6 运行时 SQL 上报响应。
 */
@Data
public class RuntimeReportResultVO {

    /** 接收事件数 */
    private Integer received;

    /** 归一化后去重指纹数 */
    private Integer uniqueFingerprints;

    /** 本轮写 module-004 kb_sql_template 数 */
    private Integer writtenTemplates;

    /** 本轮写 module-004 kb_relation 数 */
    private Integer writtenRelations;

    /** 拒收条数（含敏感/非法） */
    private Integer rejected;
}
