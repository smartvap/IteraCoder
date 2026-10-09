package com.agenthub.ai.rcagent.sqlguard.service;

import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardAuditLog;
import com.agenthub.ai.rcagent.sqlguard.mapper.SqlGuardAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 审计流水服务（FR-007）：追加 record + 分页 page。
 */
@Slf4j
@Service
public class SqlGuardAuditService {

    private final SqlGuardAuditLogMapper auditLogMapper;

    public SqlGuardAuditService(SqlGuardAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 追加审计流水。
     *
     * @return 落库后的审计主键（sql_guard_audit_log.id，MyBatis-Plus AUTO 回填）；
     *         null/异常时返回 null（审计失败不阻断主链路）
     */
    public Long record(SqlGuardAuditLog logEntity) {
        if (logEntity == null) {
            return null;
        }
        Date now = new Date();
        logEntity.setCreateTime(now);
        logEntity.setUpdateTime(now);
        if (logEntity.getIsDeleted() == null) {
            logEntity.setIsDeleted(0);
        }
        try {
            auditLogMapper.insert(logEntity);
            return logEntity.getId();
        } catch (RuntimeException ex) {
            // 审计失败不阻断主链路（安全审计尽力而为）
            log.warn("sqlguard: 审计落库失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 审计流水分页（MP PaginationInnerInterceptor 拦截 XML）。
     */
    public com.agenthub.ai.base.common.PageResult page(String dsId, String threadId, String riskLevel,
                                                       String verdict, String guardStatus,
                                                       java.util.Date startDate, java.util.Date endDate,
                                                       int page, int pageSize) {
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        com.baomidou.mybatisplus.core.metadata.IPage<SqlGuardAuditLog> p =
                auditLogMapper.selectPageAudits(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                                Math.max(page, 1), Math.max(1, Math.min(pageSize, 100))),
                        dsId, threadId, riskLevel, verdict, guardStatus, startDate, endDate);
        return new com.agenthub.ai.base.common.PageResult(p.getTotal(), p.getRecords());
    }
}
