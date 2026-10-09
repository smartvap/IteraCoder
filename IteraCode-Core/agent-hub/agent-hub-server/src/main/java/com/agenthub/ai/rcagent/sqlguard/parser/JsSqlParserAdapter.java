package com.agenthub.ai.rcagent.sqlguard.parser;

import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardErrorCode;
import com.agenthub.ai.rcagent.sqlguard.constant.StatementClass;
import com.agenthub.ai.rcagent.sqlguard.constant.StatementType;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.ColumnRef;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.TableRef;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JSqlParser 4.9 AST 解析适配器（FR-001，适配 JSqlParser 4.9 重构 API）。
 *
 * <p>说明：JSqlParser 4.9 起对 select 模型做了重构（{@link Select} 直接作为 Statement/Expression，
 * {@link PlainSelect} 继承 Select、{@link SelectItem} 携带 Expression、无旧 SelectBody/SubSelect/
 * SelectExpressionItem/ValuesList 类），本适配器按 4.9 实际类结构编写。
 * 策略：AST 解析为主（StatementType/Table/Column/Where/JOIN/LIMIT/写形态），
 * 文本仅用于首关键字辅助归类与 EXPLAIN 前缀剥离，<b>不作为拦截判据</b>；
 * 解析失败不抛异常 → statementClass=UNKNOWN + parseWarnings，供 L1 Syntax FAIL 判定。</p>
 */
@Component
public class JsSqlParserAdapter implements SqlParser {

    private static final Logger log = LoggerFactory.getLogger(JsSqlParserAdapter.class);

    /** DDL/写语句简单类名提示（文本/类名辅助，不作主判据） */
    private static final Set<String> DML_SIMPLE = Set.of("Insert", "Update", "Delete", "Merge", "Replace",
            "Upsert", "UpsertUseValues", "Truncate", "Drop", "CreateTable", "CreateIndex", "CreateView",
            "CreateSequence", "Alter", "AlterView", "RenameTable", "Grant", "Revoke", "Comment", "RefreshTable",
            "CreateSynonym", "CreateSchema", "CreateProcedure", "CreateFunction", "DeclareStatement",
            "CreateFunctionalStatement");
    private static final Set<String> TX_SIMPLE = Set.of("Commit", "RollbackStatement", "SavepointStatement",
            "StartTransaction", "SetStatement", "LockStatement", "UnlockStatement", "ResetStatement");

    /** 聚合函数名集合 */
    private static final Set<String> AGGREGATE_FUNCS =
            Set.of("COUNT", "SUM", "AVG", "MAX", "MIN", "GROUP_CONCAT", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
                    "VAR_POP", "VAR_SAMP", "VARIANCE");

    /**
     * 列引用所在子句（用于区分「可否合法引用 SELECT 输出别名」）。
     *
     * <p>MySQL 仅 GROUP BY/HAVING/ORDER BY 允许引用输出别名，WHERE/JOIN ON/SELECT 列表引用别名属非法，
     * 故列引用需携带子句来源，供 L2 未限定列校验限定豁免范围。</p>
     */
    private enum ColumnClause { SELECT_ITEM, WHERE, JOIN_ON, GROUP_BY, HAVING, ORDER_BY }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public SqlFeature parse(String sql, String dbType) {
        if (sql == null || sql.isBlank()) {
            throw SqlGuardException.param("SQL 不能为空");
        }
        if (sql.length() > 65535) {
            throw SqlGuardException.param("SQL 过长");
        }
        DbType resolved = DbType.parse(dbType);
        if (resolved == null) {
            throw new SqlGuardException(SqlGuardErrorCode.DIALECT_UNSUPPORTED, "不支持的数据库类型");
        }

        String trimmed = sql.trim();
        String firstKeyword = firstKeyword(trimmed);
        boolean explain = "EXPLAIN".equalsIgnoreCase(firstKeyword);
        String normalized = stripTrailingSemicolon(trimmed);
        if (explain) {
            // "EXPLAIN" 长度为 7，必须按实际关键字长度剥离，避免 "N SELECT..." 残留导致解析失败
            normalized = stripTrailingSemicolon(trimmed.substring("EXPLAIN".length()).trim());
        }
        if (normalized == null || normalized.isBlank()) {
            return unknown("EXPLAIN 后无 SQL");
        }
        if (containsMultiStatement(normalized)) {
            return writeFeature(StatementType.MULTI, StatementClass.MULTI,
                    "包含多条语句，按 MULTI 写形态处理（不做文本匹配判据）");
        }

        try {
            // 宽松解析：JSqlParser 4.9 默认不识别方括号标识符，需显式启用（支持多方言 [DB].[T] 报价）；
            // 仅增强可解析面，不改变只读校验语义（解析后由 stripIdentifierQuotes 兜底剥离残留引号）。
            CCJSqlParser parser = CCJSqlParserUtil.newParser(normalized);
            parser.withSquareBracketQuotation(true);
            Statement statement = parser.Statement();
            return parseStatement(statement, explain, resolved);
        } catch (Exception ex) {
            log.debug("sqlguard: SQL 解析失败(非阻断) dbType={} warning={}", dbType, ex.getMessage());
            SqlFeature feature = unknown("SQL 语法/方言解析失败: " + safeMessage(ex.getMessage()));
            // 文本辅助：非只读首关键字 → 写形态标记（不作为拦截判据）
            String kw = firstKeyword(normalized);
            if (isWriteKeyword(kw)) {
                feature.setStatementClass(StatementClass.NON_SELECT.name());
                feature.setWriteIntent(true);
                feature.getParseWarnings().add("首关键字为非只读语句，按写形态提示（文本辅助不构成拦截判据）");
            }
            return feature;
        }
    }

    /**
     * 解析成功后的 Statement 分类与特征提取。
     */
    private SqlFeature parseStatement(Statement statement, boolean explain, DbType dbType) {
        SqlFeature feature = emptyFeature();
        // SELECT（含 EXPLAIN 剥离后的 SELECT）
        if (statement instanceof Select select) {
            extractSelectFeature(feature, select, explain);
            if (explain) {
                feature.getParseWarnings().add("EXPLAIN 前缀已剥离，按 SELECT 解析");
            }
        } else {
            String cls = statement.getClass().getSimpleName();
            if (DML_SIMPLE.contains(cls)) {
                if (cls.startsWith("Create") || cls.startsWith("Alter") || cls.startsWith("Drop")
                        || cls.startsWith("Truncate") || cls.startsWith("Rename") || cls.startsWith("Grant")
                        || cls.startsWith("Revoke") || cls.startsWith("Comment")) {
                    feature.setStatementType(StatementType.DDL.name());
                    feature.setStatementClass(StatementClass.DDL.name());
                } else {
                    feature.setStatementType(StatementType.DML.name());
                    feature.setStatementClass(StatementClass.WRITE.name());
                }
            } else if (TX_SIMPLE.contains(cls)) {
                feature.setStatementType(StatementType.TRANSACTION.name());
                feature.setStatementClass(StatementClass.TRANSACTION.name());
            } else if (cls.contains("Statement") || cls.equals("Statements")) {
                feature.setStatementType(StatementType.UNKNOWN.name());
                feature.setStatementClass(StatementClass.NON_SELECT.name());
                feature.getParseWarnings().add("无法精确归类的语句(" + cls + ")，按非只读写形态 BLOCK 处置");
            } else {
                feature.setStatementType(StatementType.UNKNOWN.name());
                feature.setStatementClass(StatementClass.NON_SELECT.name());
            }
            feature.setWriteIntent(true);
        }
        return feature;
    }

    /**
     * SELECT 特征提取：PlainSelect 详细提取；SetOperationList/ParenthesedSelect 递归提取并退化为集合表提取。
     */
    private void extractSelectFeature(SqlFeature feature, Select select, boolean explain) {
        feature.setStatementType(StatementType.SELECT.name());
        feature.setStatementClass(StatementClass.READ_ONLY.name());
        feature.setWriteIntent(false);
        feature.setSelectStar(false);

        if (select instanceof PlainSelect plainSelect) {
            extractPlainSelect(feature, plainSelect);
            // 顶层查询的 FROM/JOIN 内联视图输出列对外层任意子句可见 → inlineView=true 登记派生列
            // （与 extractPlainSelect 内的两处调用等价幂等，captureDerivedColumns 自带去重）
            collectTablesOfPlain(feature, plainSelect, true);
        } else if (select instanceof SetOperationList setOp) {
            feature.setHasSubquery(true);
            feature.setJoinCount(0);
            feature.setWhereCount(0);
            feature.getParseWarnings().add("UNION/集合查询结构，已尽力提取各分支表清单");
            extractSetOperation(feature, setOp);
        } else if (select instanceof ParenthesedSelect parenthesed) {
            feature.setHasSubquery(true);
            feature.getParseWarnings().add("括号化 SELECT 结构，按内部查询提取");
            Select inner = parenthesed.getSelect();
            if (inner != null) {
                extractSelectFeature(feature, inner, explain);
            }
        } else if (select instanceof WithItem withItem) {
            // 顶层 WITH 仅在 PlainSelect 中被携带，兜底按内部 select 提取
            feature.setHasSubquery(true);
            Select inner = withItem.getSelect();
            if (inner != null) {
                extractSelectFeature(feature, inner, explain);
            }
        } else {
            feature.setHasSubquery(true);
            feature.getParseWarnings().add("未能精细解析的 SELECT 结构，表清单经 TablesNamesFinder 兜底");
        }
    }

    private void extractSetOperation(SqlFeature feature, SetOperationList setOp) {
        List<Select> selects = setOp.getSelects();
        if (selects != null) {
            for (Select s : selects) {
                // 集合查询各分支自身的 FROM/JOIN 内联视图输出列不对外层可见 → inlineView=false
                if (s instanceof PlainSelect ps) {
                    collectTablesOfPlain(feature, ps, false);
                } else if (s instanceof SetOperationList sub) {
                    extractSetOperation(feature, sub);
                } else if (s instanceof ParenthesedSelect p) {
                    Select inner = p.getSelect();
                    if (inner instanceof PlainSelect ps2) {
                        collectTablesOfPlain(feature, ps2, false);
                    }
                }
            }
        }
    }

    private void extractPlainSelect(SqlFeature feature, PlainSelect ps) {
        // SELECT 投影项：列/聚合/SELECT * 识别（SELECT * 指顶层直接 AllColumns/AllTableColumns）
        List<SelectItem<?>> items = ps.getSelectItems();
        if (items != null) {
            for (SelectItem<?> item : items) {
                // 捕获 SELECT 输出别名：供 L2 未限定列校验豁免 ORDER BY/GROUP BY 对输出别名的引用（非真实表列）
                captureOutputAlias(feature, item.getAlias());
                Expression expr = item.getExpression();
                if (expr instanceof AllColumns || expr instanceof AllTableColumns) {
                    feature.setSelectStar(true);
                } else {
                    collectExpression(feature, expr, true, false, ColumnClause.SELECT_ITEM, 0);
                }
            }
        }
        // FROM 主表
        FromItem from = ps.getFromItem();
        if (from != null) {
            if (from instanceof Table table) {
                addTable(feature, table);
            } else if (from instanceof ParenthesedSelect parenthesed) {
                feature.setHasSubquery(true);
                // FROM 内联视图（派生表）：输出列对外层任意子句可见 → inlineView=true 登记派生列
                appendSubSelectTables(feature, parenthesed, true);
            } else if (from instanceof TableFunction) {
                feature.setHasSubquery(true);
                feature.getParseWarnings().add("FROM 为表函数，列级特征有限");
            }
        }
        // JOIN
        List<Join> joins = ps.getJoins();
        int joinCount = joins == null ? 0 : joins.size();
        feature.setJoinCount(joinCount);
        if (joins != null) {
            for (Join join : joins) {
                feature.getJoinTypeSet().add(resolveJoinType(join));
                FromItem right = join.getRightItem();
                if (right instanceof Table table) {
                    addTable(feature, table);
                } else if (right instanceof ParenthesedSelect parenthesed) {
                    feature.setHasSubquery(true);
                    // JOIN 右项内联视图（派生表）：输出列对外层任意子句可见 → inlineView=true 登记派生列
                    appendSubSelectTables(feature, parenthesed, true);
                } else if (right instanceof TableFunction) {
                    feature.setHasSubquery(true);
                }
                Expression on = join.getOnExpression();
                if (on != null) {
                    collectExpression(feature, on, false, true, ColumnClause.JOIN_ON, 0);
                }
            }
        }
        // WHERE
        Expression where = ps.getWhere();
        feature.setWhereCount(where == null ? 0 : countWhereComplexity(where));
        if (where != null) {
            collectExpression(feature, where, false, false, ColumnClause.WHERE, 0);
        }
        // DISTINCT
        feature.setHasDistinct(ps.getDistinct() != null);
        // GROUP BY / HAVING
        GroupByElement groupBy = ps.getGroupBy();
        if (groupBy != null) {
            var groupRefs = groupBy.getGroupByExpressions();
            List<?> refs = groupRefs == null ? List.of() : groupRefs.getExpressions();
            feature.setGroupByCount(refs == null ? 0 : refs.size());
            if (refs != null) {
                for (Object g : refs) {
                    if (g instanceof Expression ge) {
                        collectExpression(feature, ge, false, false, ColumnClause.GROUP_BY, 0);
                    }
                }
            }
            Expression having = ps.getHaving();
            if (having != null) {
                collectExpression(feature, having, false, false, ColumnClause.HAVING, 0);
            }
        } else {
            feature.setGroupByCount(0);
        }
        // ORDER BY
        List<net.sf.jsqlparser.statement.select.OrderByElement> orderBy = ps.getOrderByElements();
        feature.setOrderByCount(orderBy == null ? 0 : orderBy.size());
        if (orderBy != null) {
            for (var ob : orderBy) {
                if (ob.getExpression() != null) {
                    collectExpression(feature, ob.getExpression(), false, false, ColumnClause.ORDER_BY, 0);
                }
            }
        }
        // LIMIT（Select 基类）
        net.sf.jsqlparser.statement.select.Limit limit = ps.getLimit();
        if (limit != null && limit.getRowCount() != null) {
            feature.setHasLimit(true);
            feature.setLimitValue(extractIntLiteral(limit.getRowCount()));
        } else {
            feature.setHasLimit(false);
            feature.setLimitValue(null);
        }
    }

    /**
     * 递归收集表达式列/子查询/聚合。
     *
     * @param clause 列引用所在子句（SELECT 列表/WHERE/JOIN ON/GROUP BY/HAVING/ORDER BY），随递归下传
     */
    private void collectExpression(SqlFeature feature, Expression expr, boolean selectItem,
                                   boolean join, ColumnClause clause, int depth) {
        if (expr == null || depth > 10) {
            return;
        }
        if (expr instanceof Column col) {
            addColumnRef(feature, col, selectItem, join, false, clause);
        } else if (expr instanceof Function fn) {
            boolean agg = isAggregateFunction(fn);
            if (agg) {
                feature.getAggregateColumns().add(describeFunction(fn));
            }
            if (fn.getParameters() != null) {
                List<?> args = fn.getParameters().getExpressions();
                if (args != null) {
                    for (Object a : args) {
                        if (a instanceof Column c) {
                            addColumnRef(feature, c, selectItem, join, agg, clause);
                        } else if (a instanceof Expression ae) {
                            collectExpression(feature, ae, selectItem, join, clause, depth + 1);
                        }
                    }
                }
            }
        } else if (expr instanceof Parenthesis p) {
            collectExpression(feature, p.getExpression(), selectItem, join, clause, depth + 1);
        } else if (expr instanceof BinaryExpression bin) {
            collectExpression(feature, bin.getLeftExpression(), selectItem, join, clause, depth + 1);
            collectExpression(feature, bin.getRightExpression(), selectItem, join, clause, depth + 1);
        } else if (expr instanceof InExpression in) {
            collectExpression(feature, in.getLeftExpression(), selectItem, join, clause, depth + 1);
            Expression right = in.getRightExpression();
            if (right instanceof Select) {
                feature.setHasSubquery(true);
                // IN 子查询：其投影别名在外层作用域不可见，不得登记派生列（否则外层同名未限定列被误放行）
                appendSubSelectTables(feature, (ParenthesedSelect) right, false);
            }
        } else if (expr instanceof Between b) {
            collectExpression(feature, b.getLeftExpression(), selectItem, join, clause, depth + 1);
        } else if (expr instanceof IsNullExpression isn) {
            collectExpression(feature, isn.getLeftExpression(), selectItem, join, clause, depth + 1);
        } else if (expr instanceof ExistsExpression ex) {
            feature.setHasSubquery(true);
            Expression right = ex.getRightExpression();
            if (right instanceof ParenthesedSelect p) {
                // EXISTS 子查询：其投影别名在外层作用域不可见，不得登记派生列
                appendSubSelectTables(feature, p, false);
            }
        } else if (expr instanceof NotExpression ne) {
            collectExpression(feature, ne.getExpression(), selectItem, join, clause, depth + 1);
        } else if (expr instanceof SignedExpression se) {
            collectExpression(feature, se.getExpression(), selectItem, join, clause, depth + 1);
        } else if (expr instanceof CaseExpression ce) {
            if (ce.getSwitchExpression() != null) {
                collectExpression(feature, ce.getSwitchExpression(), selectItem, join, clause, depth + 1);
            }
            if (ce.getWhenClauses() != null) {
                for (WhenClause wc : ce.getWhenClauses()) {
                    collectExpression(feature, wc.getWhenExpression(), selectItem, join, clause, depth + 1);
                    collectExpression(feature, wc.getThenExpression(), selectItem, join, clause, depth + 1);
                }
            }
            if (ce.getElseExpression() != null) {
                collectExpression(feature, ce.getElseExpression(), selectItem, join, clause, depth + 1);
            }
        } else if (expr instanceof Select sel && !(expr instanceof PlainSelect)) {
            feature.setHasSubquery(true);
            if (sel instanceof ParenthesedSelect p) {
                // 表达式中的标量子查询：其投影别名在外层作用域不可见，不得登记派生列
                appendSubSelectTables(feature, p, false);
            }
        }
        // 其他表达式不产生列
    }

    /**
     * 捕获 SELECT 投影项的输出别名（别名可能带引号，需 strip 后去重入库）。
     *
     * <p>输出别名不是真实表列，L2 未限定列校验需据此豁免，避免 ORDER BY/GROUP BY
     * 引用别名时被误判为「字段不存在」。</p>
     */
    private void captureOutputAlias(SqlFeature feature, Alias alias) {
        if (alias == null || feature.getOutputAliases() == null) {
            return;
        }
        String name = stripIdentifierQuotes(alias.getName());
        if (name == null || name.isBlank()) {
            return;
        }
        for (String existing : feature.getOutputAliases()) {
            if (existing != null && existing.equalsIgnoreCase(name)) {
                return;
            }
        }
        feature.getOutputAliases().add(name);
    }

    private void addColumnRef(SqlFeature feature, Column col, boolean selectItem, boolean join,
                              boolean aggregate, ColumnClause clause) {
        ColumnRef ref = new ColumnRef();
        Table colTable = col.getTable();
        // 列归属表的 schema 限定（如 DB_A.T1.x 中的 DB_A；别名场景通常为空）
        ref.setSchema(stripIdentifierQuotes(colTable == null ? null : colTable.getSchemaName()));
        ref.setTable(colTable == null ? null : colTable.getName());
        // 列名与表名/schema/别名同口径归一：JSqlParser 4.9 保留反引号/双引号，若不剥离，
        // L2 与数据库元数据（无引号）比较时会把 `status` 误判为「字段不存在」（合法 SQL 被误拦）
        ref.setColumn(stripIdentifierQuotes(col.getColumnName()));
        ref.setIsSelectItem(selectItem);
        ref.setIsJoin(join);
        ref.setIsWhere(!selectItem && !join);
        ref.setIsAggregate(aggregate);
        // 子句来源标记：供 L2 判断是否可套用「SELECT 输出别名豁免」（仅 GROUP BY/HAVING/ORDER BY 可引用别名）
        ref.setIsGroupBy(clause == ColumnClause.GROUP_BY);
        ref.setIsHaving(clause == ColumnClause.HAVING);
        ref.setIsOrderBy(clause == ColumnClause.ORDER_BY);
        boolean dup = false;
        for (ColumnRef existing : feature.getColumns()) {
            // 子句标记纳入去重维度：同一列分别出现在 WHERE 与 ORDER BY 时不得合并，
            // 否则 WHERE 引用可能因复用了 ORDER BY 条目的豁免标记而被误放行（H10 同类漏网）
            if (eq(existing.getTable(), ref.getTable()) && eq(existing.getColumn(), ref.getColumn())
                    && existing.where() == ref.where() && existing.selectItem() == ref.selectItem()
                    && existing.groupBy() == ref.groupBy() && existing.having() == ref.having()
                    && existing.orderBy() == ref.orderBy()) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            feature.getColumns().add(ref);
        }
    }

    private void addTable(SqlFeature feature, Table table) {
        // JSqlParser 对反引号限定名的表名可能保留引号，统一归一化（无引号时为恒等）
        String name = stripIdentifierQuotes(table.getName());
        if (name == null) {
            return;
        }
        TableRef ref = new TableRef();
        ref.setName(name);
        // 库/Schema 限定（如 DB_A.T1 中的 DB_A；无则 null，保持旧行为）
        ref.setSchema(stripIdentifierQuotes(table.getSchemaName()));
        ref.setAlias(table.getAlias() == null ? null : table.getAlias().getName());
        // 去重按 (schema, name) 二元比较：避免 DB_A.T 与 DB_B.T 被误判为同一表而丢失一个
        boolean exists = feature.getTables().stream()
                .anyMatch(t -> eq(t.getSchema(), ref.getSchema()) && eq(t.getName(), ref.getName()));
        if (!exists) {
            feature.getTables().add(ref);
        }
    }

    /**
     * 收集子查询涉及的表（并按作用域决定是否登记其投影输出别名）。
     *
     * @param inlineView true 表示该子查询是当前查询 FROM/JOIN 中的内联视图（派生表），
     *                   其输出列对外层任意子句可见，可登记到 {@code derivedColumns} 获得豁免；
     *                   false 表示 IN/EXISTS/标量子查询等表达式内子查询，其投影别名在外层作用域
     *                   不可见（MySQL 亦不可见），<b>不得</b>登记派生列（否则外层同名未限定列被误放行）。
     */
    private void appendSubSelectTables(SqlFeature feature, ParenthesedSelect parenthesed, boolean inlineView) {
        Select inner = parenthesed == null ? null : parenthesed.getSelect();
        if (inner instanceof PlainSelect ps) {
            // 仅内联视图（派生表）的输出列进入外层豁免集合：外层可在任意子句合法引用内联视图输出列，
            // 不登记会导致如 SELECT cnt FROM (SELECT COUNT(*) AS cnt ...) x 被 L2 误判列不存在；
            // 而 IN/EXISTS/标量子查询的别名外层不可见，登记会造成漏放（H10 同类）
            if (inlineView) {
                captureDerivedColumns(feature, ps);
            }
            // 作用域随 FROM/JOIN 链传播：内嵌内联视图的输出列可经无别名投影向上透传（合法）；
            // 而 IN/EXISTS/标量子查询整棵子树不可见，其内嵌内联视图同样不得登记派生列
            collectTablesOfPlain(feature, ps, inlineView);
        } else if (inner instanceof SetOperationList setOp) {
            if (inlineView) {
                // UNION/集合内联视图：MySQL 以首个分支的投影列名作为派生表输出列名
                captureSetOperationDerivedColumns(feature, setOp);
            }
            extractSetOperation(feature, setOp);
        } else if (inner instanceof ParenthesedSelect p2) {
            // 纯括号嵌套：作用域随外层传递（形如 FROM ((SELECT ...)) x）
            appendSubSelectTables(feature, p2, inlineView);
        }
    }

    /**
     * 捕获集合查询（UNION 等）作为内联视图时的输出列名（取首个分支，与 MySQL 列名口径一致）。
     */
    private void captureSetOperationDerivedColumns(SqlFeature feature, SetOperationList setOp) {
        List<Select> selects = setOp.getSelects();
        if (selects == null || selects.isEmpty()) {
            return;
        }
        Select first = selects.get(0);
        if (first instanceof PlainSelect ps) {
            captureDerivedColumns(feature, ps);
        } else if (first instanceof SetOperationList sub) {
            captureSetOperationDerivedColumns(feature, sub);
        } else if (first instanceof ParenthesedSelect p && p.getSelect() instanceof PlainSelect ps2) {
            captureDerivedColumns(feature, ps2);
        }
    }

    /**
     * 捕获派生表投影输出列别名（仅别名，未命名投影列通常直接来自真实表，无需登记）。
     *
     * <p>与顶层 SELECT 输出别名（{@code outputAliases}）分开存放：内联视图输出列是外层的「表列」，
     * 任意子句均可引用；而顶层输出别名仅 ORDER BY/GROUP BY/HAVING 可引用。</p>
     */
    private void captureDerivedColumns(SqlFeature feature, PlainSelect ps) {
        List<SelectItem<?>> items = ps.getSelectItems();
        if (items == null || feature.getDerivedColumns() == null) {
            return;
        }
        for (SelectItem<?> item : items) {
            Alias alias = item == null ? null : item.getAlias();
            if (alias == null) {
                continue;
            }
            String name = stripIdentifierQuotes(alias.getName());
            if (name == null || name.isBlank()) {
                continue;
            }
            boolean exists = feature.getDerivedColumns().stream()
                    .anyMatch(d -> d != null && d.equalsIgnoreCase(name));
            if (!exists) {
                feature.getDerivedColumns().add(name);
            }
        }
    }

    /**
     * 收集 PlainSelect 的 FROM/JOIN 表清单。
     *
     * @param inlineView 该 PlainSelect 的 FROM/JOIN 内联视图输出列是否对最外层查询可见
     *                   （顶层查询传 true；嵌套/集合分支内传 false），决定是否登记派生列
     */
    private void collectTablesOfPlain(SqlFeature feature, PlainSelect ps, boolean inlineView) {
        FromItem from = ps.getFromItem();
        if (from instanceof Table table) {
            addTable(feature, table);
        } else if (from instanceof ParenthesedSelect p) {
            appendSubSelectTables(feature, p, inlineView);
        }
        List<Join> joins = ps.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                FromItem right = join.getRightItem();
                if (right instanceof Table table) {
                    addTable(feature, table);
                } else if (right instanceof ParenthesedSelect p) {
                    appendSubSelectTables(feature, p, inlineView);
                }
            }
        }
    }

    private SqlFeature emptyFeature() {
        SqlFeature feature = new SqlFeature();
        feature.setTables(new ArrayList<>());
        feature.setColumns(new ArrayList<>());
        feature.setJoinTypeSet(new LinkedHashSet<>());
        feature.setAggregateColumns(new ArrayList<>());
        feature.setOutputAliases(new ArrayList<>());
        feature.setDerivedColumns(new ArrayList<>());
        feature.setParseWarnings(new ArrayList<>());
        feature.setSelectStar(false);
        feature.setWhereCount(0);
        feature.setJoinCount(0);
        feature.setHasSubquery(false);
        feature.setGroupByCount(0);
        feature.setOrderByCount(0);
        feature.setHasLimit(false);
        feature.setHasDistinct(false);
        feature.setWriteIntent(false);
        return feature;
    }

    private SqlFeature writeFeature(StatementType type, StatementClass cls, String warning) {
        SqlFeature feature = emptyFeature();
        feature.setStatementType(type.name());
        feature.setStatementClass(cls.name());
        feature.setWriteIntent(true);
        feature.getParseWarnings().add(warning);
        return feature;
    }

    private SqlFeature unknown(String warning) {
        SqlFeature feature = emptyFeature();
        feature.setStatementType(StatementType.UNKNOWN.name());
        feature.setStatementClass(StatementClass.UNKNOWN.name());
        feature.setWriteIntent(false);
        feature.getParseWarnings().add(warning);
        return feature;
    }

    private int countWhereComplexity(Expression where) {
        if (where instanceof BinaryExpression bin) {
            return countWhereComplexity(bin.getLeftExpression()) + countWhereComplexity(bin.getRightExpression());
        }
        if (where instanceof Parenthesis p) {
            return countWhereComplexity(p.getExpression());
        }
        return 1;
    }

    private String resolveJoinType(Join join) {
        if (join.isLeft()) {
            return "LEFT";
        }
        if (join.isRight()) {
            return "RIGHT";
        }
        if (join.isFull()) {
            return "FULL";
        }
        if (join.isCross()) {
            return "CROSS";
        }
        if (join.isSemi()) {
            return "SEMI";
        }
        if (join.isOuter()) {
            return "OUTER";
        }
        return "INNER";
    }

    private boolean isAggregateFunction(Function fn) {
        String name = fn.getName();
        return name != null && AGGREGATE_FUNCS.contains(name.toUpperCase(Locale.ROOT));
    }

    private String describeFunction(Function fn) {
        String name = fn.getName() == null ? "FUNC" : fn.getName().toUpperCase(Locale.ROOT);
        String arg = "";
        if (fn.getParameters() != null && fn.getParameters().getExpressions() != null
                && !fn.getParameters().getExpressions().isEmpty()) {
            Object first = fn.getParameters().getExpressions().get(0);
            if (first instanceof Column c) {
                arg = (c.getTable() != null && c.getTable().getName() != null ? c.getTable().getName() + "." : "")
                        + c.getColumnName();
            } else if (first instanceof AllColumns) {
                arg = "*";
            } else {
                arg = "expr";
            }
        }
        return name + "/" + arg;
    }

    private Integer extractIntLiteral(Expression expr) {
        if (expr instanceof net.sf.jsqlparser.expression.LongValue lv) {
            return (int) lv.getValue();
        }
        if (expr instanceof net.sf.jsqlparser.expression.DoubleValue dv) {
            return (int) dv.getValue();
        }
        return null;
    }

    private String firstKeyword(String sql) {
        String t = WHITESPACE.matcher(sql == null ? "" : sql.trim()).replaceAll(" ").trim();
        if (t.isEmpty()) {
            return "";
        }
        int end = t.indexOf(' ');
        return (end < 0 ? t : t.substring(0, end)).toUpperCase(Locale.ROOT);
    }

    private boolean isWriteKeyword(String kw) {
        return DML_SIMPLE.stream().anyMatch(s -> s.equalsIgnoreCase(kw))
                || TX_SIMPLE.stream().anyMatch(s -> s.equalsIgnoreCase(kw));
    }

    private String stripTrailingSemicolon(String sql) {
        String s = sql == null ? "" : sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private boolean containsMultiStatement(String normalized) {
        return normalized != null && normalized.contains(";");
    }

    /**
     * 归一化标识符：trim 并去除成对引号（反引号/双引号/方括号三风格）；结果为空视为无 schema（返回 null）。
     *
     * <p>注意：此处<b>不转大写</b>（与 {@code WhitelistRule.normalizeTable} 的区别）——SqlFeature 中的
     * 表名/Schema 需保留原始大小写供下游语义使用，大小写不敏感比较由消费方按需处理。
     * JSqlParser 4.9 默认配置下方括号会直接导致解析失败（未启用 allowSquareBracketQuotation），
     * 此分支为防御性补齐：一旦启用该特性或 JSqlParser 行为变化，方括号可被正确剥离。</p>
     */
    private String stripIdentifierQuotes(String identifier) {
        if (identifier == null) {
            return null;
        }
        String s = identifier.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s.isEmpty() ? null : s;
    }

    private boolean eq(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "无细节";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
