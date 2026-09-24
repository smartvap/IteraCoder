package com.agenthub.ai.datasource.dto;

import com.agenthub.ai.dbaccess.model.WhitelistRule;
import lombok.Data;
import lombok.ToString;

/**
 * 测试连接一次性请求（api-contract DsTestConnectionRequest）。
 *
 * <p>passwordForTest 为<b>一次性测试密码</b>，仅存在于请求内存：禁止写日志/审计/健康记录/接口响应
 * （BR-002 / AGENTS.md 5.4）。toString 排除该字段，防止 DTO 整体打印泄密。</p>
 */
@Data
public class DsTestConnectionRequest {

    /** 已存在数据源测试时可传（复用其配置中的白名单）；候选测试可不传 */
    private String dsId;

    /** 库类型（必填） */
    private String dbType;

    /** JDBC 连接串（必填，禁内嵌账密） */
    private String jdbcUrl;

    /** 只读账号（必填） */
    private String readonlyUser;

    /** 一次性测试密码（必填，仅内存使用） */
    @ToString.Exclude
    private String passwordForTest;

    /** 待校验白名单（可空） */
    private WhitelistRule whitelist;

    /** 短超时 ms（默认 3000，≤10000） */
    private Integer timeoutMs;
}
