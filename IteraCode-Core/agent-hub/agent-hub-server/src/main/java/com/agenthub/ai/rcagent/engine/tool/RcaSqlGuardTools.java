package com.agenthub.ai.rcagent.engine.tool;

import com.agenthub.ai.rcagent.engine.node.RcaNodeUtils;
import com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckItem;
import com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlSchemaCheckRequest;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * RCAgent SQL 防护轻量工具（module-006 门面适配，仅本模块使用，不侵入 sqlguard）。
 * <p>
 * 供 SQL 生成节点在落库前做 schema 预检与只读自检；依赖服务缺失/失败时返回可读降级文本，
 * 不阻断主链路——最终风险分级仍以 RcaValidateNode 调 SqlGuardService.validate 为准。</p>
 */
@Slf4j
public class RcaSqlGuardTools {

    private final SqlGuardService sqlGuardService;

    public RcaSqlGuardTools(SqlGuardService sqlGuardService) {
        this.sqlGuardService = sqlGuardService;
    }

    /**
     * Schema 预检：检查 SQL 涉及的表/字段是否在数据源 schema 字典确认。
     *
     * @return 预检报告文本（供 SQL Agent 自查），空字符串表示未配置
     */
    public String schemaCheck(String dsId, String sql) {
        if (sqlGuardService == null || dsId == null || dsId.isBlank() || sql == null || sql.isBlank()) {
            return "";
        }
        try {
            SqlSchemaCheckRequest req = SqlSchemaCheckRequest.builder()
                    .dsId(dsId)
                    .sql(sql)
                    .includeRelations(true)
                    .build();
            SchemaCheckResult result = sqlGuardService.schemaCheck(req);
            if (result == null) {
                return "[Schema 预检] 无返回";
            }
            StringBuilder sb = new StringBuilder("[Schema 预检] 来源=")
                    .append(result.getSource() == null ? "UNAVAILABLE" : result.getSource())
                    .append("，passed=").append(Boolean.TRUE.equals(result.getPassed())).append("\n");
            List<SchemaCheckItem> checks = result.getChecks();
            if (checks == null || checks.isEmpty()) {
                sb.append("（无明细）");
            } else {
                int shown = 0;
                for (SchemaCheckItem item : checks) {
                    if (shown >= 20) break;
                    sb.append("- ").append(item.getType()).append(" ").append(item.getTarget())
                            .append(" exists=").append(Boolean.TRUE.equals(item.getExists()))
                            .append(" ").append(item.getReason() == null ? "" : item.getReason()).append("\n");
                    shown++;
                }
            }
            return RcaNodeUtils.truncate(sb.toString(), 2000);
        } catch (Exception e) {
            log.warn("RCAgent Schema 预检失败(降级): {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return "[Schema 预检] 执行失败: " + RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200);
        }
    }

    /** 是否可提供 schema 预检能力（便于 SQL 生成节点决定是否提示） */
    public boolean available() {
        return sqlGuardService != null;
    }
}
