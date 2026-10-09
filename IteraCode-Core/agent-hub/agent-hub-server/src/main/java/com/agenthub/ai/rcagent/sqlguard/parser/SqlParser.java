package com.agenthub.ai.rcagent.sqlguard.parser;

import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;

/**
 * SQL Parser SPI（FR-001，可替换 JSqlParser 或其他实现）。
 *
 * <p>解析失败不抛异常：返回 statementClass=UNKNOWN + parseWarnings，由 L1 Syntax 判定；
 * 仅方言不支持/参数非法抛 SqlGuardException（43006/PARAM）。文本匹配仅作辅助不作为拦截判据。</p>
 */
public interface SqlParser {

    /**
     * 解析 SQL 为 AST 特征。
     *
     * @param sql   候选 SQL（首关键字须为 SELECT/EXPLAIN；DML/DDL 传入即识别为写形态）
     * @param dbType MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE
     * @return AST 特征模型
     */
    SqlFeature parse(String sql, String dbType);
}
