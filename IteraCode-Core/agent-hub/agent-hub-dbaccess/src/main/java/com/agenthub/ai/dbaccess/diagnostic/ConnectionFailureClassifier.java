package com.agenthub.ai.dbaccess.diagnostic;

import com.agenthub.ai.dbaccess.model.DbType;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 连接失败原因分类器（FR-BE-001~003，data-model §3.2）。
 *
 * <p>无状态纯函数：读取 {@link SQLException#getSQLState()} / {@link SQLException#getErrorCode()} /
 * {@link SQLException#getMessage()}，按<b>严格优先级</b>归入 {@link ConnectionFailureCategory}：</p>
 * <ol>
 *   <li>① SQLState：前缀 {@code 08}（网络/连接类）→ 网络不可达；其 message 含 {@code Connection refused} 时归「连接被拒绝」；</li>
 *   <li>② vendor errorCode：{@code 1049}→库不存在、<b>{@code 1044}（库级，先判）</b>→库无权限、{@code 1045}（账号级）→认证失败；</li>
 *   <li>③ message 关键词：Unknown database / Access denied ... to database / Access denied for user /
 *       Connection refused / Communications link failure / connect timed out / SocketTimeout / UnknownHostException；</li>
 *   <li>④ 兜底 {@link ConnectionFailureCategory#UNKNOWN}。</li>
 * </ol>
 *
 * <p>副作用：无。不打印日志、不修改异常、不把驱动原始 message 并入返回值（避免凭据/地址外泄）。
 * 对 {@code null} 异常、{@code null}/空白 SQLState、{@code null} message、{@code errorCode=0} 均安全兜底，
 * <b>永不返回 null、永不抛异常</b>（NFR-BE-006 / BP-01 / BP-11）。</p>
 */
public final class ConnectionFailureClassifier {

    /** vendor errorCode：库不存在（Unknown database） */
    private static final int ERR_DATABASE_NOT_FOUND = 1049;

    /** vendor errorCode：库级无权限（Access denied for user ... to database）——必须先于 1045 判定 */
    private static final int ERR_DATABASE_ACCESS_DENIED = 1044;

    /** vendor errorCode：账号或密码错误（Access denied for user） */
    private static final int ERR_AUTH_FAILED = 1045;

    private ConnectionFailureClassifier() {
    }

    /**
     * 按优先级归类连接异常。
     *
     * @param e 连接/初始化阶段抛出的 SQLException（可空）
     * @return 失败类别；不可归类时返回 {@link ConnectionFailureCategory#UNKNOWN}（永不为 null）
     */
    public static ConnectionFailureCategory classify(SQLException e) {
        if (e == null) {
            return ConnectionFailureCategory.UNKNOWN;
        }
        String sqlState = safeUpper(e.getSQLState());
        String message = safeUpper(e.getMessage());

        // ① SQLState：08* 为网络/连接类（08001 连接被拒绝、08S01 通信链路异常等）
        if (sqlState.startsWith("08")) {
            if (message.contains("CONNECTION REFUSED")) {
                return ConnectionFailureCategory.CONNECTION_REFUSED;
            }
            return ConnectionFailureCategory.NETWORK_UNREACHABLE;
        }

        // ② vendor errorCode：1044（库级）必须先于 1045（账号级），避免库无权限被误归为认证失败
        int errorCode = e.getErrorCode();
        if (errorCode == ERR_DATABASE_ACCESS_DENIED) {
            return ConnectionFailureCategory.DATABASE_ACCESS_DENIED;
        }
        if (errorCode == ERR_AUTH_FAILED) {
            return ConnectionFailureCategory.AUTH_FAILED;
        }
        if (errorCode == ERR_DATABASE_NOT_FOUND) {
            return ConnectionFailureCategory.DATABASE_NOT_FOUND;
        }

        // ③ message 关键词（顺序与语义：库不存在 → 库无权限（含 to database）→ 认证失败 → 拒绝 → 网络）
        if (message.contains("UNKNOWN DATABASE")) {
            return ConnectionFailureCategory.DATABASE_NOT_FOUND;
        }
        if (message.contains("TO DATABASE")) {
            return ConnectionFailureCategory.DATABASE_ACCESS_DENIED;
        }
        if (message.contains("ACCESS DENIED FOR USER")) {
            return ConnectionFailureCategory.AUTH_FAILED;
        }
        if (message.contains("CONNECTION REFUSED")) {
            return ConnectionFailureCategory.CONNECTION_REFUSED;
        }
        if (message.contains("COMMUNICATIONS LINK FAILURE")
                || message.contains("CONNECT TIMED OUT")
                || message.contains("SOCKETTIMEOUT")
                || message.contains("UNKNOWNHOSTEXCEPTION")) {
            return ConnectionFailureCategory.NETWORK_UNREACHABLE;
        }

        // ④ 兜底
        return ConnectionFailureCategory.UNKNOWN;
    }

    /**
     * 带库类型的归类重载（预留：当前判定与库类型无关，保留签名便于后续按方言族细化）。
     *
     * @param e      连接异常（可空）
     * @param dbType 库类型（可空，当前不参与判定）
     * @return 失败类别（永不为 null）
     */
    public static ConnectionFailureCategory classify(SQLException e, DbType dbType) {
        return classify(e);
    }

    /** 安全归一：null/空白 → 空串，统一大写，避免 NPE 与大小写差异导致的漏判 */
    private static String safeUpper(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().toUpperCase(Locale.ROOT);
    }
}
