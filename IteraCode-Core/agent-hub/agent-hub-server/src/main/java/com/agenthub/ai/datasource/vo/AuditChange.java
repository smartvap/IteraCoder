package com.agenthub.ai.datasource.vo;

import lombok.Data;

/**
 * 审计变更明细项（敏感字段只记 changed 标记，不记值）。
 *
 * <p>register 记字段清单：仅 field；update 记字段级 before/after；
 * readonly_password_ref 等敏感字段只输出 {@code {"field":"readonly_password_ref","changed":true}}。</p>
 */
@Data
public class AuditChange {

    /** 变更字段名 */
    private String field;

    /** 变更前值（敏感字段不填） */
    private Object before;

    /** 变更后值（敏感字段不填） */
    private Object after;

    /** 是否发生变更（敏感字段专用标记） */
    private Boolean changed;
}
