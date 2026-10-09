package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.engine.tool.RcaSqlGuardTools;
import com.agenthub.ai.rcagent.sqlguard.execute.SqlExecutionGuardService;
import com.agenthub.ai.rcagent.sqlguard.model.GuardedQueryResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardQuery;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardRequest;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 跨源 SQL 防护适配器（brownfield 适配层，module-003）。
 *
 * <p>包装既有能力（**均零改动、不改方法签名**）：{@link SqlGuardService}（{@code validate} / {@code schemaCheck}）、
 * {@link SqlExecutionGuardService}（{@code executeGuarded}）与既有门面 {@link RcaSqlGuardTools}。
 * 逐源、逐片独立调用，语义与既有单源节点一致：**BLOCK 不执行**、**MEDIUM/HIGH 需人工放行**；
 * 服务缺失由调用方 fail-closed（本适配器在 {@code validate} 中直接抛 {@link BusinessException}
 * 50010 语义，与 {@code RcaValidateNode} 一致）。</p>
 */
@Slf4j
@Component
public class CrossSourceSqlGuardAdapter {

    /** 校验/执行的时间上限（秒），与既有单源节点保持一致 */
    private static final int MAX_SECONDS = 10;

    private final SqlGuardService sqlGuardService;
    private final SqlExecutionGuardService executionGuardService;
    private final RcaSqlGuardTools sqlGuardTools;

    public CrossSourceSqlGuardAdapter(
            ObjectProvider<SqlGuardService> sqlGuardServiceProvider,
            ObjectProvider<SqlExecutionGuardService> executionGuardServiceProvider) {
        // ObjectProvider 保证容器启动不受可选依赖缺失影响（缺服务时 available()=false，调用方 fail-closed）
        this.sqlGuardService = sqlGuardServiceProvider.getIfAvailable();
        this.executionGuardService = executionGuardServiceProvider.getIfAvailable();
        // 复用既有门面（仅包装，不修改既有类）
        this.sqlGuardTools = new RcaSqlGuardTools(sqlGuardService);
    }

    /** 校验能力是否可用（false 时调用方必须 fail-closed，禁止降级放行） */
    public boolean available() {
        return sqlGuardService != null;
    }

    /** 执行能力是否可用（false 时调用方判 FAILED，禁止用部分结果冒充成功） */
    public boolean executionAvailable() {
        return executionGuardService != null;
    }

    /**
     * 逐源（逐片）只读校验。
     *
     * @param dsId     数据源 ID
     * @param sql      待校验 SQL（写形态由既有策略 BLOCK）
     * @param plan     单源计划视图（可空 ⇒ L3 SKIP）
     * @param maxRows  行数上限
     * @param threadId 会话线程 ID（审计可追溯）
     * @return 五级验证报告
     * @throws BusinessException 校验服务缺失（fail-closed，50010 语义）
     */
    public ValidationReport validate(String dsId, String sql, SqlPlanView plan, int maxRows, String threadId) {
        if (sqlGuardService == null) {
            log.warn("RCAgent 跨源校验：SqlGuardService 未注入，按失败终止（fail-closed）");
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                    "SQL 验证服务不可用（sql-validate-guard 未接入或数据源不可达），本次跨源会话已按失败终止，"
                            + "请检查依赖模块配置后重新发起或切换数据源重试（50010）");
        }
        SqlGuardRequest.SqlGuardRequestBuilder builder = SqlGuardRequest.builder()
                .dsId(dsId)
                .sql(sql)
                .maxRows(maxRows)
                .maxSeconds(MAX_SECONDS)
                .threadId(threadId);
        if (plan != null) {
            builder.plan(plan);
        }
        return sqlGuardService.validate(builder.build());
    }

    /** Schema 预检（复用既有门面，逐源按 dsId 天然可用；失败返回可读降级文本，不抛异常） */
    public String schemaCheck(String dsId, String sql) {
        return sqlGuardTools.schemaCheck(dsId, sql);
    }

    /**
     * 逐源（逐片）受保护只读执行。
     *
     * @param dsId             数据源 ID
     * @param sql              待执行只读 SQL
     * @param maxRows          行数上限
     * @param requireApproval  MEDIUM/HIGH 需人工放行标记
     * @param threadId         会话线程 ID
     * @return 执行结果；执行服务缺失时返回 {@code null}（由调用方 fail-closed 判失败）
     */
    public GuardedQueryResult execute(String dsId, String sql, int maxRows, boolean requireApproval, String threadId) {
        if (executionGuardService == null) {
            log.warn("RCAgent 跨源执行：SqlExecutionGuardService 未注入（占位契约缺失）");
            return null;
        }
        SqlGuardQuery query = SqlGuardQuery.builder()
                .dsId(dsId)
                .sql(sql)
                .maxRows(maxRows)
                .maxSeconds(MAX_SECONDS)
                .requireManualApproval(requireApproval)
                .threadId(threadId)
                .build();
        return executionGuardService.executeGuarded(query);
    }
}
