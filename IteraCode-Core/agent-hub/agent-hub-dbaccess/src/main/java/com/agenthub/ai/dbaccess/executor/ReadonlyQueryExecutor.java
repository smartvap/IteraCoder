package com.agenthub.ai.dbaccess.executor;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.constant.DbAccessConstants;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.QueryRequest;
import com.agenthub.ai.dbaccess.model.QueryResult;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import com.agenthub.ai.dbaccess.model.SensitivePolicy;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 只读查询执行器（api-contract ReadonlyQueryExecutor）。
 *
 * <p>流程：解析 ds_id → 校验 SQL 只读（SELECT/EXPLAIN，SELECT * 按配置拒绝）→ 白名单（表/列，
 * 默认放行语义：未配置白名单时全部表放行，安全由只读账号 + 敏感字段 DENY + SELECT * 限制 +
 * maxRows/maxSeconds 兜底；显式配置后按 mode 约束）→ 敏感字段策略（DENY 直接拒 / MASK 挂脱敏）
 * → maxRows/maxSeconds 内执行 → 行数截断 → 返回 QueryResult。
 * 全部走 PreparedStatement + Statement 级超时；连接失败/超时转结构化异常，message 不含连接细节。</p>
 */
@Service
public class ReadonlyQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReadonlyQueryExecutor.class);
    private static final int EXPLAIN_MAX_ROWS = 200;

    /** 写意图锁/写倾向子句（只读通道一律拒绝） */
    private static final Pattern WRITE_INTENT = Pattern.compile(
            "(?i)\\bfor\\s+update\\b|\\block\\s+in\\s+share\\s+mode\\b|\\bfor\\s+share\\b");

    private final DataSourceRegistry registry;
    private final SensitiveFieldRegistry sensitiveFieldRegistry;
    private final SqlReadonlyValidator validator;
    private final DbAccessProperties properties;

    public ReadonlyQueryExecutor(DataSourceRegistry registry,
                                 SensitiveFieldRegistry sensitiveFieldRegistry,
                                 SqlReadonlyValidator validator,
                                 DbAccessProperties properties) {
        this.registry = registry;
        this.sensitiveFieldRegistry = sensitiveFieldRegistry;
        this.validator = validator;
        this.properties = properties;
    }

    /**
     * 在限制内只读执行并截断返回（explainOnly=false 主入口）。
     */
    public QueryResult execute(QueryRequest request) {
        validateQueryRequest(request);
        return doExecute(request, request.isExplainOnly());
    }

    /**
     * 仅返回执行计划，不返回业务行数据（explainOnly=true）。
     */
    public QueryResult explain(QueryRequest request) {
        validateQueryRequest(request);
        QueryRequest explainRequest = QueryRequest.builder()
                .dsId(request.getDsId())
                .sql(request.getSql())
                .maxRows(request.getMaxRows())
                .maxSeconds(request.getMaxSeconds())
                .sensitivePolicy(request.getSensitivePolicy())
                .explainOnly(true)
                .build();
        return doExecute(explainRequest, true);
    }

    // ------------------------------------------------------------------
    // 参数校验
    // ------------------------------------------------------------------

    private void validateQueryRequest(QueryRequest request) {
        if (request == null) {
            throw DbAccessException.param("查询请求不能为空");
        }
        String dsId = request.getDsId();
        if (dsId == null || dsId.isBlank()) {
            throw DbAccessException.param("ds_id 不能为空");
        }
        if (!dsId.matches(DbAccessConstants.DS_ID_PATTERN)) {
            throw DbAccessException.param("ds_id 格式非法");
        }
        String sql = request.getSql();
        if (sql == null || sql.isBlank()) {
            throw DbAccessException.param("SQL 不能为空");
        }
        if (sql.length() > DbAccessConstants.SQL_MAX_LENGTH) {
            throw DbAccessException.param("SQL 过长");
        }
        int moduleMaxRows = properties.defaultLimitsModel().getMaxRows();
        int moduleMaxSeconds = properties.defaultLimitsModel().getMaxSeconds();
        if (request.getMaxRows() != null && (request.getMaxRows() <= 0 || request.getMaxRows() > moduleMaxRows)) {
            throw DbAccessException.param("maxRows 超限");
        }
        if (request.getMaxSeconds() != null
                && (request.getMaxSeconds() <= 0 || request.getMaxSeconds() > moduleMaxSeconds)) {
            throw DbAccessException.param("maxSeconds 超限");
        }
    }

    // ------------------------------------------------------------------
    // 主执行
    // ------------------------------------------------------------------

    private QueryResult doExecute(QueryRequest request, boolean explainOnly) {
        long started = System.currentTimeMillis();
        String dsId = request.getDsId();
        DataSourceContext context = registry.resolve(dsId);
        ResourceLimits dsLimits = context.getLimits();
        int effMaxRows = effectiveMaxRows(dsLimits.getMaxRows(), request.getMaxRows());
        int effMaxSeconds = effectiveMaxSeconds(dsLimits.getMaxSeconds(), request.getMaxSeconds());
        SensitivePolicy policy = request.getSensitivePolicy() != null
                ? request.getSensitivePolicy()
                : (dsLimits.getSensitivePolicy() == null ? SensitivePolicy.DENY : dsLimits.getSensitivePolicy());

        String sql = request.getSql();
        // 统一校验面与执行面：均使用「去注释 + 去尾分号」的规范化文本，
        // 避免校验（去注释）与执行（保留注释）剥离差异造成应用层只读拦截盲区（问题4）。
        String validation = validator.forValidation(sql);
        String execution = validation;

        // 1) 只读 + 单条
        String keyword = validator.firstKeyword(validation);
        if (!"SELECT".equals(keyword) && !"EXPLAIN".equals(keyword)) {
            throw new DbAccessException(DbAccessErrorCode.SQL_REJECTED, "仅支持只读 SELECT/EXPLAIN 查询");
        }
        if (validation.contains(";")) {
            throw new DbAccessException(DbAccessErrorCode.SQL_REJECTED, "仅支持单条只读 SELECT 查询");
        }
        if (WRITE_INTENT.matcher(validation).find()) {
            throw new DbAccessException(DbAccessErrorCode.SQL_REJECTED, "仅支持只读查询（禁止行锁/共享锁）");
        }

        // 2) SELECT * 策略
        boolean allowStar = !Boolean.FALSE.equals(dsLimits.getAllowSelectStar());
        if (!allowStar && validator.hasTopLevelSelectStar(validation)) {
            throw new DbAccessException(DbAccessErrorCode.SQL_REJECTED, "SELECT * 已被数据源策略拒绝");
        }

        // 3) 库集合边界（数据源可访问库集合；未声明则不设边界，旧单库行为不变）
        checkSpaceBoundary(context, validation);

        // 4) 白名单（表级 + 单表列级；未配置白名单默认放行）
        Set<String> tables = validator.referencedTables(validation);
        checkWhitelist(context, tables, validation);

        // 5) 敏感字段 DENY 预检（保守：命中 HIGH 字段标识即拒，避免对敏感值发起查询）
        List<SensitiveFieldRule> sensitiveRules = sensitiveFieldRegistry.loadByDs(dsId);
        if (policy == SensitivePolicy.DENY) {
            preCheckSensitiveDeny(validation, tables, sensitiveRules);
        }

        try {
            if (explainOnly) {
                return doExplain(context, execution, effMaxSeconds, dsId, started, policy, sensitiveRules, tables);
            }
            return doSelect(context, execution, effMaxRows, effMaxSeconds, dsId, started, policy, sensitiveRules, tables);
        } catch (DbAccessException e) {
            throw e;
        } catch (SQLException e) {
            throw translateSqlException(e);
        } catch (RuntimeException e) {
            throw new DbAccessException(DbAccessErrorCode.SYSTEM_ERROR, "SQL 执行失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 库集合边界
    // ------------------------------------------------------------------

    /**
     * 库集合边界校验（数据源级「可访问库集合」层，独立于表级白名单）。
     *
     * <p>语义：当数据源声明了可访问库集合 {@code spaceNames}（DsConfigView.spaceNames）时，
     * SQL 中以 schema/库前缀限定引用的表（如 {@code DB_A.T1}）其库前缀必须落在该集合内，否则拒绝。
     * 无 schema 前缀（连接默认库，归 spaceName/catalog）不拦截；{@code spaceNames} 为空/null 时不设边界，
     * 保持旧单库行为不变（任务 A/C 的采集侧回落语义一致）。</p>
     *
     * <p>与表级白名单的关系：本校验是「数据源可访问库集合」这层；白名单（{@link WhitelistRule}）保持原样
     * 按纯表名（{@link SqlReadonlyValidator#referencedTables}）校验，两层各自独立、共同收敛访问面。</p>
     *
     * <p>错误码：复用 {@link DbAccessErrorCode#WHITELIST_DENIED}（访问范围拒绝语义：库越界与表白名单越权
     * 同属「数据源授权范围外」），不新增错误码、不改错误码编号空间。</p>
     */
    private void checkSpaceBoundary(DataSourceContext context, String validation) {
        List<String> accessible = context.getConfig().getSpaceNames();
        if (accessible == null || accessible.isEmpty()) {
            // 未声明库集合 → 不做跨库边界校验（旧单库行为不变）
            return;
        }
        Set<String> qualified = validator.referencedQualifiedTables(validation);
        if (qualified.isEmpty()) {
            return;
        }
        Set<String> allowed = new HashSet<>();
        for (String space : accessible) {
            String normalized = WhitelistRule.normalizeTable(space);
            if (!normalized.isEmpty()) {
                allowed.add(normalized);
            }
        }
        for (String q : qualified) {
            String schema = SqlReadonlyValidator.schemaOf(q);
            if (schema == null || schema.isBlank()) {
                // 无库前缀 = 连接默认库（spaceName/catalog），不参与跨库边界拦截
                continue;
            }
            if (!allowed.contains(WhitelistRule.normalizeTable(schema))) {
                throw new DbAccessException(DbAccessErrorCode.WHITELIST_DENIED,
                        "库访问越界：引用的库不在数据源可访问库集合内");
            }
        }
    }

    // ------------------------------------------------------------------
    // 白名单
    // ------------------------------------------------------------------

    private void checkWhitelist(DataSourceContext context, Set<String> tables, String validation) {
        if (tables.isEmpty()) {
            return;
        }
        WhitelistRule whitelist = context.getConfig().getWhitelist();
        // 默认放行语义（用户确认）：whitelist 未配置（null）→ 全部表放行；
        // 安全由只读账号 + 敏感字段 DENY + SELECT * 限制 + maxRows/maxSeconds 兜底。
        if (whitelist == null) {
            return;
        }
        // 显式配置后按 mode 约束：ALLOW_ONLY 白名单/DENY_ONLY 黑名单/通配符均由 WhitelistRule 判定
        for (String table : tables) {
            if (!whitelist.allowTable(table)) {
                throw new DbAccessException(DbAccessErrorCode.WHITELIST_DENIED, "白名单拒绝访问：表不在授权范围");
            }
        }
        // 单表 + 精确列级限制时执行列级校验（hasColumnRestriction 仅对 ALLOW_ONLY 精确授权表返回 true；
        // DENY_ONLY/ALL/通配表返回 false 自然跳过列级；多表场景依赖表级校验 + 敏感字段兜底，避免启发式误伤）
        if (tables.size() == 1) {
            String table = tables.iterator().next();
            if (whitelist.hasColumnRestriction(table)) {
                List<String> columns = validator.projectionColumns(validation);
                if (columns.isEmpty()) {
                    // 无法可靠解析投影且存在列级限制 → fail-closed 拒绝
                    throw new DbAccessException(DbAccessErrorCode.WHITELIST_DENIED,
                            "白名单拒绝访问：列级限制下无法确认投影字段");
                }
                for (String column : columns) {
                    if (!whitelist.allowColumn(table, column)) {
                        throw new DbAccessException(DbAccessErrorCode.WHITELIST_DENIED,
                                "白名单拒绝访问：列不在授权范围");
                    }
                }
            }
        }
    }

    private void preCheckSensitiveDeny(String validation, Set<String> tables, List<SensitiveFieldRule> sensitiveRules) {
        if (sensitiveRules.isEmpty()) {
            return;
        }
        for (SensitiveFieldRule rule : sensitiveRules) {
            // 生效等级采用 effectiveLevel()：level 缺省/为 null 一律按 HIGH（fail-closed），
            // 防止配置漏配 level 导致 DENY 预检静默跳过、敏感字段明文返回（问题2）
            if (rule.effectiveLevel() != SensitiveLevel.HIGH) {
                continue;
            }
            boolean tableMatched = rule.getTable() == null || rule.getTable().isBlank()
                    || tables.isEmpty()
                    || tables.contains(rule.getTable().trim().toUpperCase(Locale.ROOT));
            if (tableMatched && validator.containsIdentifier(validation, rule.getColumn())) {
                throw new DbAccessException(DbAccessErrorCode.SENSITIVE_DENIED, "命中敏感字段，访问被拒绝");
            }
        }
    }

    // ------------------------------------------------------------------
    // 数据查询（SELECT）
    // ------------------------------------------------------------------

    private QueryResult doSelect(DataSourceContext context, String execution,
                                 int maxRows, int maxSeconds, String dsId, long started,
                                 SensitivePolicy policy, List<SensitiveFieldRule> sensitiveRules,
                                 Set<String> tables) throws SQLException {
        QueryResult result = QueryResult.of(dsId);
        try (Connection conn = context.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(execution)) {
            ps.setQueryTimeout(maxSeconds);
            // 服务端限制读取行数（多取 1 行用于探测是否截断），避免全量拉取
            ps.setMaxRows(maxRows + 1);
            boolean hasResult;
            try {
                hasResult = ps.execute();
            } catch (SQLTimeoutException e) {
                throw new DbAccessException(DbAccessErrorCode.TIMEOUT, "查询执行超时", e);
            } catch (SQLException e) {
                throw translateSqlException(e);
            }
            if (!hasResult) {
                throw new DbAccessException(DbAccessErrorCode.SQL_REJECTED, "仅支持只读查询（无结果集）");
            }
            try (ResultSet rs = ps.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> labels = new ArrayList<>(colCount);
                SensitiveLevel[] maskLevels = new SensitiveLevel[colCount];
                for (int i = 1; i <= colCount; i++) {
                    labels.add(meta.getColumnLabel(i));
                    maskLevels[i - 1] = SensitiveLevel.NONE;
                }
                // 结果列标签→真实投影列映射（封堵 SELECT phone AS p 别名绕过敏感判定，问题2）
                List<String> projectionByPosition = validator.projectionColumnsByPosition(execution);
                boolean deny = planSensitive(execution, labels, maskLevels, sensitiveRules, policy, tables,
                        projectionByPosition);
                if (deny) {
                    throw new DbAccessException(DbAccessErrorCode.SENSITIVE_DENIED, "命中敏感字段，访问被拒绝");
                }
                result.setColumnNames(labels);
                int fetched = 0;
                boolean maskedAny = false;
                while (fetched < maxRows && rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        String label = labels.get(i - 1);
                        Object value = rs.getObject(i);
                        SensitiveLevel level = maskLevels[i - 1];
                        if (policy == SensitivePolicy.MASK && level != null && level != SensitiveLevel.NONE
                                && value != null) {
                            maskedAny = true;
                            row.put(label, sensitiveFieldRegistry.mask(String.valueOf(value), level));
                        } else {
                            row.put(label, value);
                        }
                    }
                    result.getRows().add(row);
                    fetched++;
                }
                boolean truncated = false;
                if (fetched >= maxRows) {
                    try {
                        truncated = rs.next();
                    } catch (SQLException e) {
                        // 服务端已在 maxRows+1 截断，忽略探测异常视为截断完成
                        truncated = false;
                    }
                }
                result.setRowCount(fetched);
                result.setTruncated(truncated);
                result.setSensitiveMasked(maskedAny);
                result.setCostMs(System.currentTimeMillis() - started);
                return result;
            }
        }
    }

    /**
     * 依据结果集列标签与真实投影列制定敏感策略（DENY→拒绝；MASK→标记列级脱敏；ALLOW_BY_PERMISSION→放行）。
     *
     * <p>防别名绕过：优先按「真实投影列」与敏感规则列比对（SELECT phone AS p 时 label=p、真实列=PHONE 仍命中）；
     * 子查询/派生表别名重命名（SELECT p FROM (SELECT phone AS p ...)）由解析器做来源可信度判定置 null，
     * 此处 realColumn==null 时按 SQL 文本命中敏感标识符保守整列脱敏，禁止明文回退。</p>
     */
    private boolean planSensitive(String validationText, List<String> labels, SensitiveLevel[] maskLevels,
                                  List<SensitiveFieldRule> rules, SensitivePolicy policy, Set<String> tables,
                                  List<String> projectionByPosition) {
        boolean deny = false;
        boolean starExpanded = validator.hasTopLevelSelectStar(validationText);
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            String realColumn = i < projectionByPosition.size() ? projectionByPosition.get(i) : null;
            SensitiveLevel hit = SensitiveLevel.NONE;
            for (SensitiveFieldRule rule : rules) {
                if (matchRuleColumn(rule, tables, label) || matchRuleColumn(rule, tables, realColumn)) {
                    // effectiveLevel()：level 缺省/null 一律按 HIGH（问题2）
                    SensitiveLevel ruleLevel = rule.effectiveLevel();
                    if (ruleLevel.weight() > hit.weight()) {
                        hit = ruleLevel;
                    }
                }
            }
            // 保守兜底：来源不可信（realColumn 为 null，含函数/表达式列、以及经派生表来源判定为
            // 不可信的外层列）时，按 SQL 文本是否命中敏感标识符保守整列脱敏/拒绝，禁止明文回退。
            // SELECT * 展开默认不触发（结果集 label 即真实列名、逐列精确比对）；
            // 但 SELECT * FROM (派生表…) 时 label 可能是内层别名（如 phone AS p），来源仍不可信，
            // 故派生表场景同样走保守兜底（封堵子查询/派生表别名重命名绕过，问题1）。
            if (realColumn == null && (!starExpanded || validator.hasDerivedTable(validationText))) {
                SensitiveLevel conservative = conservativeHit(validationText, rules, tables);
                if (conservative != null && conservative.weight() > hit.weight()) {
                    hit = conservative;
                }
            }
            if (policy == SensitivePolicy.DENY && hit == SensitiveLevel.HIGH) {
                deny = true;
            } else if (policy == SensitivePolicy.MASK && hit != SensitiveLevel.NONE) {
                maskLevels[i] = hit;
            }
        }
        return deny;
    }

    /**
     * 保守文本命中：SQL 文本含某表匹配敏感规则列标识符时返回最高等级（用于无法解析投影列的兜底脱敏）。
     */
    private SensitiveLevel conservativeHit(String validationText, List<SensitiveFieldRule> rules, Set<String> tables) {
        SensitiveLevel hit = null;
        for (SensitiveFieldRule rule : rules) {
            boolean tableMatched = rule.getTable() == null || rule.getTable().isBlank()
                    || tables.isEmpty()
                    || tables.contains(rule.getTable().trim().toUpperCase(Locale.ROOT));
            if (tableMatched && validator.containsIdentifier(validationText, rule.getColumn())
                    // effectiveLevel()：level 缺省/null 一律按 HIGH（问题2）
                    && (hit == null || rule.effectiveLevel().weight() > hit.weight())) {
                hit = rule.effectiveLevel();
            }
        }
        return hit;
    }

    private boolean matchRuleColumn(SensitiveFieldRule rule, Set<String> tables, String column) {
        if (column == null || column.isBlank()) {
            return false;
        }
        String col = rule.getColumn() == null ? "" : rule.getColumn().trim();
        String candidate = column.trim();
        if (!col.equalsIgnoreCase(candidate)) {
            return false;
        }
        if (rule.getTable() == null || rule.getTable().isBlank()) {
            return true;
        }
        return tables.isEmpty() || tables.contains(rule.getTable().trim().toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // 执行计划（EXPLAIN）
    // ------------------------------------------------------------------

    private QueryResult doExplain(DataSourceContext context, String execution, int maxSeconds,
                                  String dsId, long started, SensitivePolicy policy,
                                  List<SensitiveFieldRule> rules, Set<String> tables) throws SQLException {
        QueryResult result = QueryResult.of(dsId);
        boolean oracleFamily = context.getDialect().dbType().isOracleFamily();
        try (Connection conn = context.getDataSource().getConnection()) {
            if (!oracleFamily) {
                // MySQL 族：EXPLAIN <sql> 直接返回计划结果集
                String planSql = "EXPLAIN " + execution;
                try (PreparedStatement ps = conn.prepareStatement(planSql)) {
                    ps.setQueryTimeout(maxSeconds);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Map<String, Object>> rows = readPlanRows(rs, EXPLAIN_MAX_ROWS);
                        result.setColumnNames(rows.isEmpty() ? new ArrayList<>() : new ArrayList<>(rows.get(0).keySet()));
                        result.setRows(rows);
                        result.setExplainPlan(JSON.toJSONString(rows));
                    }
                }
            } else {
                // Oracle 族（POC）：EXPLAIN PLAN FOR <sql> 后经 DBMS_XPLAN.DISPLAY 读取
                String planSql = "EXPLAIN PLAN FOR " + execution;
                try (PreparedStatement ps = conn.prepareStatement(planSql)) {
                    ps.setQueryTimeout(maxSeconds);
                    ps.execute();
                }
                try (PreparedStatement ps2 = conn.prepareStatement(
                        "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)")) {
                    ps2.setQueryTimeout(maxSeconds);
                    try (ResultSet rs = ps2.executeQuery()) {
                        List<Map<String, Object>> rows = readPlanRows(rs, EXPLAIN_MAX_ROWS);
                        result.setRows(rows);
                        result.setExplainPlan(JSON.toJSONString(rows));
                    }
                }
            }
            result.setCostMs(System.currentTimeMillis() - started);
            return result;
        } catch (SQLTimeoutException e) {
            throw new DbAccessException(DbAccessErrorCode.TIMEOUT, "查询执行超时", e);
        }
    }

    private List<Map<String, Object>> readPlanRows(ResultSet rs, int cap) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rows.size() < cap && rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 限制计算与异常翻译
    // ------------------------------------------------------------------

    private int effectiveMaxRows(Integer dsMaxRows, Integer requestMaxRows) {
        // 「请求值 ∩ 数据源配置值 ∩ 模块默认值」中最严格者，模块默认作为硬上界，
        // 防止数据源覆盖(>模块默认)将执行上限放大破坏 SC-003（问题3）。
        int module = properties.defaultLimitsModel().getMaxRows();
        int fromDs = dsMaxRows == null ? module : Math.min(module, dsMaxRows);
        return requestMaxRows == null ? fromDs : Math.min(fromDs, requestMaxRows);
    }

    private int effectiveMaxSeconds(Integer dsMaxSeconds, Integer requestMaxSeconds) {
        int module = properties.defaultLimitsModel().getMaxSeconds();
        int fromDs = dsMaxSeconds == null ? module : Math.min(module, dsMaxSeconds);
        return requestMaxSeconds == null ? fromDs : Math.min(fromDs, requestMaxSeconds);
    }

    private DbAccessException translateSqlException(SQLException e) {
        if (e instanceof SQLTimeoutException) {
            return new DbAccessException(DbAccessErrorCode.TIMEOUT, "查询执行超时", e);
        }
        String state = e.getSQLState();
        if (state != null) {
            if (state.startsWith("08") || state.startsWith("28")) {
                return new DbAccessException(DbAccessErrorCode.CONNECTION_FAILED, "数据库连接失败", e);
            }
            if (state.startsWith("42")) {
                return new DbAccessException(DbAccessErrorCode.SQL_REJECTED, "SQL 执行被数据库拒绝", e);
            }
        }
        return new DbAccessException(DbAccessErrorCode.SYSTEM_ERROR, "SQL 执行失败", e);
    }
}
