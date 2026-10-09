package com.agenthub.ai.rcagent.sqlguard.mapper;

import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * sql_guard_audit_log 审计流水 Mapper。
 */
@Mapper
public interface SqlGuardAuditLogMapper extends BaseMapper<SqlGuardAuditLog> {

    /**
     * 审计流水分页查询（按 ds_id/risk/verdict/guard_status/时间区间过滤，不含已删除）。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<SqlGuardAuditLog> selectPageAudits(IPage<SqlGuardAuditLog> page,
                                            @Param("dsId") String dsId,
                                            @Param("threadId") String threadId,
                                            @Param("riskLevel") String riskLevel,
                                            @Param("verdict") String verdict,
                                            @Param("guardStatus") String guardStatus,
                                            @Param("startDate") Date startDate,
                                            @Param("endDate") Date endDate);

    /**
     * 审计总数（配合分页；MP 分页场景可省略，此处供非分页兜底）。
     */
    long countAudits(@Param("dsId") String dsId,
                     @Param("threadId") String threadId,
                     @Param("riskLevel") String riskLevel,
                     @Param("verdict") String verdict,
                     @Param("guardStatus") String guardStatus,
                     @Param("startDate") Date startDate,
                     @Param("endDate") Date endDate);
}
