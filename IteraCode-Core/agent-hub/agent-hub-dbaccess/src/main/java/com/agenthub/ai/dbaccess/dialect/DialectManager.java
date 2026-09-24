package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方言管理器：按 db_type 返回 SqlDialect（解析带缓存）。
 *
 * <p>路由规则：MYSQL / OCEANBASE_MYSQL → {@link MySqlDialect}；ORACLE / OCEANBASE_ORACLE → {@link OracleDialect}。
 * 未注册 db_type 且无默认兜底时抛 {@link DbAccessErrorCode#DIALECT_UNSUPPORTED}。</p>
 *
 * <p>性能：命中缓存单次 &lt;1ms，未命中（枚举注册表直接索引）&lt;5ms，满足 NFR-BE-001。</p>
 */
@Component
public class DialectManager {

    private final Map<DbType, SqlDialect> dialects = new ConcurrentHashMap<>();

    public DialectManager() {
        SqlDialect mysql = new MySqlDialect();
        SqlDialect oracle = new OracleDialect();
        dialects.put(DbType.MYSQL, mysql);
        dialects.put(DbType.OCEANBASE_MYSQL, mysql);
        dialects.put(DbType.ORACLE, oracle);
        dialects.put(DbType.OCEANBASE_ORACLE, oracle);
    }

    /**
     * 扩展注册（新增数据库类型时装配，不改调用方）。
     */
    public void register(DbType dbType, SqlDialect dialect) {
        if (dbType == null || dialect == null) {
            throw DbAccessException.param("方言注册参数非法");
        }
        dialects.put(dbType, dialect);
    }

    /**
     * 按库类型解析方言。
     *
     * @param dbType 库类型
     * @return 方言实现
     * @throws DbAccessException 未注册方言时抛 DIALECT_UNSUPPORTED
     */
    public SqlDialect dialectOf(DbType dbType) {
        if (dbType == null) {
            throw new DbAccessException(DbAccessErrorCode.DIALECT_UNSUPPORTED, "数据库方言不支持：db_type 为空");
        }
        SqlDialect dialect = dialects.get(dbType);
        if (dialect == null) {
            throw new DbAccessException(DbAccessErrorCode.DIALECT_UNSUPPORTED,
                    "数据库方言不支持：" + dbType.getDisplayName());
        }
        return dialect;
    }
}
