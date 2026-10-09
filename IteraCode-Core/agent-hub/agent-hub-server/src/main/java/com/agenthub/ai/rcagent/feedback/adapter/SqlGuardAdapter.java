package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * module-006 sql-validate-guard → feedback-loop AST diff 适配器（brownfield 边界）。
 *
 * <p>复用 {@link SqlGuardService#parse(String, String)} 获取 SqlFeature 做双 SQL AST
 * 确定性对比（SqlDiffAnalyzer 消费）。module-006 parse 对「语法不支持/非 SELECT」不抛异常
 * （返回 UNKNOWN/WRITE 特征），SqlDiffAnalyzer 据此降级为归一化文本 diff；此处仅将
 * 「参数非法类」解析问题（SqlGuardException 40000）保持返回 null 降级，其余依赖级异常
 * 统一收敛为 45005（NFR-BE-003），日志不落 SQL 原文。</p>
 */
@Slf4j
@Component
public class SqlGuardAdapter {

    private final SqlGuardService sqlGuardService;

    public SqlGuardAdapter(SqlGuardService sqlGuardService) {
        this.sqlGuardService = sqlGuardService;
    }

    /**
     * SQL AST 解析（安全封装）：语法不支持/解析不可用场景由调用方降级文本 diff，不向外抛原文。
     *
     * @param sql    SQL（日志禁止打原文）
     * @param dbType 方言（MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE）
     */
    public SqlFeature parse(String sql, String dbType) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        try {
            return sqlGuardService.parse(sql, dbType == null || dbType.isBlank() ? "MYSQL" : dbType);
        } catch (SqlGuardException e) {
            // 参数非法（SQL 空/超长等）视为解析不可用：返回 null 由 SqlDiffAnalyzer 文本降级
            if (e.getCode() == 40000) {
                log.warn("feedback: SQL AST 参数非法(降级文本 diff): {}",
                        FeedbackSafeText.truncate(e.getMessage(), 200));
                return null;
            }
            // 其余 module-006 依赖级异常：统一收敛 45005，不向外抛解析细节
            log.warn("feedback: SQL AST 解析依赖异常(降级处理): {}",
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "SQL 解析服务不可用，请稍后重试");
        } catch (RuntimeException e) {
            log.warn("feedback: SQL AST 解析异常(降级处理): {}",
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "SQL 解析服务不可用，请稍后重试");
        }
    }
}
