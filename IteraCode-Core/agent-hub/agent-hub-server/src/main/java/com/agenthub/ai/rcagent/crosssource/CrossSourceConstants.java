package com.agenthub.ai.rcagent.crosssource;

import java.util.regex.Pattern;

/**
 * 跨源 join 常量集（module-003 cross-source-join）。
 *
 * <p>集中承载对外文案、合并规则编码、下推字面量安全白名单正则等，禁止魔法字符串散落。
 * 所有对外文案均**不得**含 dsId / 库名 / 表名 / SQL 原文 / 连接串 / 账号（api-contract §8 S-01~S-03）。</p>
 */
public final class CrossSourceConstants {

    private CrossSourceConstants() {
    }

    // ==================== 源序号渲染 ====================

    /** 源序号前缀（对外仅暴露序号，不暴露 dsId） */
    public static final String SOURCE_PREFIX = "源 ";

    // ==================== 合并结果版式（§4.5.2 逐字符） ====================

    /** 合并结果表头 */
    public static final String MERGE_HEADER = "===== 跨源关联结果 =====";
    /** 覆盖数据源行前缀 */
    public static final String COVER_SOURCE_PREFIX = "覆盖数据源：";
    /** 行数行前缀 */
    public static final String ROW_COUNT_PREFIX = "行数：";
    /** 列清单行前缀 */
    public static final String COLUMN_PREFIX = "列：";
    /** 截断标记（行数行内） */
    public static final String TRUNCATED_YES = "（已截断：是）";
    /** 未截断标记（行数行内） */
    public static final String TRUNCATED_NO = "（已截断：否）";
    /** 明细行前缀 */
    public static final String ROW_LINE_PREFIX = "| ";
    /** 明细单元格分隔符 */
    public static final String CELL_SEPARATOR = " ; ";
    /** 剩余行提示前缀 */
    public static final String REMAIN_ROW_PREFIX = "... 共 ";
    /** 剩余行提示后缀 */
    public static final String REMAIN_ROW_SUFFIX = " 行";
    /** 合并结果说明行 */
    public static final String MERGE_FOOTER = "（说明：本次查询覆盖多个数据源，明细为按关联键合并后的结果）";
    /** NULL 字面量（值归一口径） */
    public static final String NULL_LITERAL = "NULL";
    /** 明细单元格值截断长度（沿用 RcaExecuteNode.displayValue 口径） */
    public static final int CELL_MAX_LEN = 60;

    // ==================== CROSS_SOURCE_NOTE 文案（data-model §5.9 逐字符） ====================

    /** 规划成功 */
    public static final String NOTE_PLAN_OK_PREFIX = "跨源关联：已生成关联计划（覆盖 ";
    /** 规划成功后缀 */
    public static final String NOTE_PLAN_OK_SUFFIX = " 个数据源）";
    /** 执行成功后缀：已按上限截断 */
    public static final String NOTE_TRUNCATED_SUFFIX = "（已按上限截断）";
    /** 执行成功后缀：未下推键过滤 */
    public static final String NOTE_NO_PUSHDOWN_SUFFIX = "；未下推键过滤";
    /** 降级单源 */
    public static final String NOTE_DEGRADED = "跨源规划未通过校验，已降级为单源查询（主源）";
    /** 源数超上限（明确拒绝，不静默截断） */
    public static final String NOTE_SOURCE_COUNT_EXCEEDED = "跨源关联最多支持 2 个数据源";
    /** 用户显式指定 join key 校验失败 */
    public static final String NOTE_USER_KEY_INVALID = "跨源查询失败：用户指定的关联键不可用";
    /** 规划 LLM 调用失败 */
    public static final String NOTE_LLM_FAILED = "跨源规划失败：规划服务不可用，已降级为单源查询（主源）";

    // ==================== 失败文案（含「源 N」序号，脱敏） ====================

    /** 执行失败前缀 */
    public static final String FAIL_PREFIX = "跨源查询失败：";
    /** 源执行失败 */
    public static final String FAIL_EXEC = "执行失败";
    /** 源查询超时 */
    public static final String FAIL_TIMEOUT = "查询超时";
    /** 源 SQL 被安全体系拦截 */
    public static final String FAIL_BLOCKED_PREFIX = "已拦截：源 ";
    /** 拦截后缀 */
    public static final String FAIL_BLOCKED_SUFFIX = " SQL 未通过安全分级";
    /** 源未能生成有效只读查询 */
    public static final String FAIL_SQL_MISSING_PREFIX = "执行失败：源 ";
    /** 源生成失败后缀 */
    public static final String FAIL_SQL_MISSING_SUFFIX = " 未能生成有效只读查询";
    /** 源生成失败说明（CROSS_SOURCE_NOTE） */
    public static final String FAIL_GENERATE_PREFIX = "跨源查询失败：源 ";
    /** 源生成失败说明后缀 */
    public static final String FAIL_GENERATE_SUFFIX = " 生成失败";
    /** 计划或逐源 SQL 缺失 */
    public static final String FAIL_PLAN_MISSING = "执行失败：跨源计划或逐源 SQL 缺失";
    /** 执行服务不可用 */
    public static final String FAIL_SERVICE_MISSING = "执行降级：跨源执行服务不可用，请检查依赖配置后重试";

    // ==================== State / JSON 形状 ====================

    /** 逐源 SQL 角色：探测侧（先执行，产出 join key 集合） */
    public static final String ROLE_PROBE = "PROBE";
    /** 逐源 SQL 角色：被下推侧（接收 WHERE key IN (...)） */
    public static final String ROLE_TARGET = "TARGET";
    /** 逐源 SQL 状态：首轮生成 */
    public static final String SQL_STATUS_READY = "READY";
    /** 逐源 SQL 状态：审核打回后重生成 */
    public static final String SQL_STATUS_REGENERATED = "REGENERATED";

    // ==================== 降级 / 失败原因字典（data-model §5.6、§5.7） ====================

    /** LLM 输出无法解析 */
    public static final String DEGRADE_PLAN_PARSE_FAILED = "PLAN_PARSE_FAILED";
    /** 计划未通过校验 */
    public static final String DEGRADE_PLAN_INVALID = "PLAN_INVALID";
    /** LLM 推断的 join key 不在两侧元数据中 */
    public static final String DEGRADE_JOIN_KEY_NOT_FOUND = "JOIN_KEY_NOT_FOUND";
    /** 两侧 key 类型族不可比较 */
    public static final String DEGRADE_KEY_TYPE_INCOMPARABLE = "KEY_TYPE_INCOMPARABLE";
    /** 候选源数量超上限 */
    public static final String DEGRADE_SOURCE_COUNT_EXCEEDED = "SOURCE_COUNT_EXCEEDED";
    /** 两侧元数据均不可用 */
    public static final String DEGRADE_METADATA_UNAVAILABLE = "METADATA_UNAVAILABLE";
    /** 规划 LLM 异常/空输出 */
    public static final String DEGRADE_LLM_FAILED = "LLM_FAILED";
    /** 执行状态：成功 */
    public static final String EXEC_SUCCESS = "SUCCESS";
    /** 执行状态：失败 */
    public static final String EXEC_FAILED = "FAILED";
    /** 执行状态：超时 */
    public static final String EXEC_TIMEOUT = "TIMEOUT";
    /** 执行状态：命中敏感字段 DENY */
    public static final String EXEC_REJECTED = "REJECTED";
    /** 执行状态：逐源/逐片校验为 BLOCK */
    public static final String EXEC_BLOCK_SQL = "BLOCK_SQL";
    /** 逐源生成结果为写形态 */
    public static final String EXEC_SQL_NOT_READONLY = "SQL_NOT_READONLY";
    /** 逐源无有效只读 SQL */
    public static final String EXEC_SQL_EMPTY = "SQL_EMPTY";

    /** 计划来源：LLM 规划 */
    public static final String PLAN_SOURCE_LLM = "LLM";
    /** 计划来源：用户显式指定（JOIN_HINT） */
    public static final String PLAN_SOURCE_USER_OVERRIDE = "USER_OVERRIDE";

    // ==================== 下推计划 ====================

    /** 下推模式：单条 IN */
    public static final String PUSHDOWN_SINGLE_IN = "SINGLE_IN";
    /** 下推模式：分片 IN */
    public static final String PUSHDOWN_SHARDED_IN = "SHARDED_IN";
    /** 下推模式：回退不下推 */
    public static final String PUSHDOWN_NONE = "NONE";
    /** 下推备注：开关关闭 */
    public static final String PUSHDOWN_NOTE_DISABLED = "半连接下推已关闭";
    /** 下推备注：片数超上限回退 */
    public static final String PUSHDOWN_NOTE_TOO_MANY_SHARDS = "片数超过上限，回退不下推";
    /** 下推备注：无可下推键值 */
    public static final String PUSHDOWN_NOTE_NO_SAFE_KEY = "无可下推的关联键值，回退不下推";
    /** 下推备注：存在被剔除的不安全键值（文案含数量占位） */
    public static final String PUSHDOWN_NOTE_UNSAFE_PREFIX = " 个关联键值不符合下推白名单已剔除";

    // ==================== 合并规则编码（merge_rules_applied） ====================

    /** 列名 trim + 限定前缀剥离 */
    public static final String RULE_COLUMN_NORMALIZE = "MR-01";
    /** 双侧同名冲突加 l_/r_ 前缀 */
    public static final String RULE_COLUMN_CONFLICT_PREFIX = "MR-02";
    /** 冲突仍存在时追加序号后缀 */
    public static final String RULE_COLUMN_DEDUP_SUFFIX = "MR-03";
    /** 值归一（null→NULL、trim、超 60 字符截断） */
    public static final String RULE_VALUE_NORMALIZE = "MR-04";
    /** 合并行数上限截断 */
    public static final String RULE_ROW_LIMIT = "MR-05";
    /** join 语义（INNER/LEFT，LEFT 未匹配补 NULL） */
    public static final String RULE_JOIN_SEMANTIC = "MR-06";

    /** 左侧列名冲突前缀 */
    public static final String COLUMN_PREFIX_LEFT = "l_";
    /** 右侧列名冲突前缀 */
    public static final String COLUMN_PREFIX_RIGHT = "r_";
    /** join 结果内部左侧键前缀（合并层据此还原来源侧，不出现在对外文本） */
    public static final String JOIN_KEY_LEFT = "L::";
    /** join 结果内部右侧键前缀 */
    public static final String JOIN_KEY_RIGHT = "R::";

    // ==================== 安全白名单正则（data-model §5.3，防 SQL 注入） ====================

    /** 字符串字面量白名单：字母/数字/下划线/短横线/点号/@/冒号（长度另由 key-literal-max-len 约束） */
    public static final Pattern KEY_LITERAL_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-\\.@:]+$");
    /** 日期时间字面量白名单 */
    public static final Pattern KEY_DATETIME_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?)?$");
    /** join 键（列名）白名单 */
    public static final Pattern KEY_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]+$");
    /** 限定名（库.表.列）前缀剥离 */
    public static final Pattern QUALIFIER_PREFIX_PATTERN = Pattern.compile("^[A-Za-z0-9_]+\\.+");
    /** join 键长度上限 */
    public static final int KEY_NAME_MAX_LEN = 128;

    // ==================== 元数据证据 ====================

    /** 元数据不可用（单源降级文案） */
    public static final String EVIDENCE_UNAVAILABLE = "元数据不可用";
    /** 每源证据文本长度上限 */
    public static final int EVIDENCE_MAX_LEN = 2500;
    /** 关系证据不可用标注 */
    public static final String EVIDENCE_RELATION_UNAVAILABLE = "关系证据不可用";

    /** 错误状态详情长度上限（errorStatusDetails / failureReason，脱敏） */
    public static final int ERROR_DETAIL_MAX_LEN = 300;
    /** 失败原因（SourceQueryResult.failure_reason）长度上限 */
    public static final int FAILURE_REASON_MAX_LEN = 200;
    /** 合并结果整体长度上限（沿用既有 query_result 口径） */
    public static final int QUERY_RESULT_MAX_LEN = 8000;
    /** 明细展示行数上限（沿用既有口径） */
    public static final int DISPLAY_ROW_LIMIT = 30;

    // ==================== 通用小工具（纯函数，供跨源各组件复用） ====================

    /**
     * 剥离列名限定前缀（防库名/表名泄露）：循环去除 {@code ^[A-Za-z0-9_]+\.}，
     * 如 {@code db.t.customer_id} / {@code t.customer_id} → {@code customer_id}。
     */
    public static String stripQualifier(String name) {
        if (name == null) {
            return null;
        }
        String result = name.trim();
        while (true) {
            String next = QUALIFIER_PREFIX_PATTERN.matcher(result).replaceFirst("");
            if (next.equals(result) || next.isBlank()) {
                return result;
            }
            result = next;
        }
    }

    /** 去除 SQL 结尾分号与首尾空白（注入 WHERE 条件前使用，不做语义改写） */
    public static String stripTrailingSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String result = sql.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /** 字符串截断（超出追加 {@code ...}；null 安全），用于脱敏日志/失败原因 */
    public static String truncate(String text, int maxLen) {
        if (text == null || maxLen <= 0 || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
