package com.agenthub.ai.datasource.vo;

import lombok.Data;

/**
 * 库类型字典（api-contract DbTypeOptionVO）。providerReady=false 时前端提示需先扩展 Provider。
 */
@Data
public class DbTypeOptionVO {

    /** MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE */
    private String dbType;

    /** 显示名（MySQL/Oracle/...） */
    private String label;

    /** module-002 Provider 是否就绪（未就绪可登记但不可启用） */
    private Boolean providerReady;
}
