package com.agenthub.ai.dbaccess.diagnostic;

import com.agenthub.ai.dbaccess.model.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ConnectionFailureClassifier 连接失败原因分类单测（FR-BE-001~003，data-model §3.1/§3.2）。
 *
 * <p>覆盖：SQLState 08* 分支、vendor errorCode 分支（1044/1045/1049）及其优先级、message 关键词分支、
 * 未知兜底不抛异常、null/空白健壮性、类别文案脱敏（不含 host/库名/账号/连接串原文）。</p>
 */
class ConnectionFailureClassifierTest {

    /** 脱敏断言用的敏感样例：host / 账号 / 库名 / 连接串（均不得出现在类别文案中） */
    private static final String SENSITIVE_HOST = "10.99.88.77";
    private static final String SENSITIVE_USER = "rca_reader_acct";
    private static final String SENSITIVE_DB = "ZZZSECRETDB";
    private static final String SENSITIVE_URL = "jdbc:oceanbase://10.99.88.77:2881/ZZZSECRETDB";

    private static SQLException ex(String sqlState, int errorCode, String message) {
        return new SQLException(message, sqlState, errorCode);
    }

    @Test
    @DisplayName("SQLState 08S01 / 08001 → NETWORK_UNREACHABLE（网络/连接类）")
    void should_returnNetworkUnreachable_when_sqlStateStartsWith08() {
        assertThat(ConnectionFailureClassifier.classify(
                ex("08S01", 0, "Communications link failure")))
                .isEqualTo(ConnectionFailureCategory.NETWORK_UNREACHABLE);
        assertThat(ConnectionFailureClassifier.classify(
                ex("08001", 0, "The driver could not establish a secure connection")))
                .isEqualTo(ConnectionFailureCategory.NETWORK_UNREACHABLE);
    }

    @Test
    @DisplayName("SQLState 08* 且 message 含 Connection refused → CONNECTION_REFUSED（SQLState 分支内细分）")
    void should_returnConnectionRefused_when_sqlState08AndMessageRefused() {
        assertThat(ConnectionFailureClassifier.classify(
                ex("08001", 0, "java.net.ConnectException: Connection refused")))
                .isEqualTo(ConnectionFailureCategory.CONNECTION_REFUSED);
        assertThat(ConnectionFailureClassifier.classify(
                ex("08S01", 0, "Connection refused")))
                .isEqualTo(ConnectionFailureCategory.CONNECTION_REFUSED);
    }

    @ParameterizedTest
    @CsvSource({
            "1049, DATABASE_NOT_FOUND",
            "1044, DATABASE_ACCESS_DENIED",
            "1045, AUTH_FAILED"
    })
    @DisplayName("vendor errorCode 1049/1044/1045 归类正确（SQLState 非 08*）")
    void should_classifyByErrorCode(int errorCode, ConnectionFailureCategory expected) {
        assertThat(ConnectionFailureClassifier.classify(ex("HY000", errorCode, "driver message")))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("优先级：errorCode 1044（库级）先于 1045（账号级），即使 message 同时命中两关键词")
    void should_preferDatabaseAccessDenied_when_1044And1045BothMatch() {
        SQLException e = ex("HY000", 1044,
                "Access denied for user 'rca_reader_acct'@'10.99.88.77' to database 'ZZZSECRETDB'");
        assertThat(ConnectionFailureClassifier.classify(e))
                .isEqualTo(ConnectionFailureCategory.DATABASE_ACCESS_DENIED);
    }

    @Test
    @DisplayName("优先级：errorCode 判定先于 message 关键词（1045 + message 含 to database → AUTH_FAILED）")
    void should_preferErrorCodeOverMessageKeywords() {
        SQLException e = ex("HY000", 1045,
                "Access denied for user 'rca_reader_acct'@'10.99.88.77' to database 'ZZZSECRETDB'");
        assertThat(ConnectionFailureClassifier.classify(e))
                .isEqualTo(ConnectionFailureCategory.AUTH_FAILED);
    }

    @Test
    @DisplayName("优先级：SQLState 08* 先于 vendor errorCode（08S01 + 1045 → NETWORK_UNREACHABLE）")
    void should_preferSqlStateOverErrorCode() {
        assertThat(ConnectionFailureClassifier.classify(ex("08S01", 1045, "driver message")))
                .isEqualTo(ConnectionFailureCategory.NETWORK_UNREACHABLE);
    }

    @ParameterizedTest
    @CsvSource({
            "Unknown database 'ZZZSECRETDB', DATABASE_NOT_FOUND",
            "Access denied for user 'rca_reader_acct'@'10.99.88.77' to database 'ZZZSECRETDB', DATABASE_ACCESS_DENIED",
            "Access denied for user 'rca_reader_acct'@'10.99.88.77' (using password: YES), AUTH_FAILED",
            "java.net.ConnectException: Connection refused, CONNECTION_REFUSED",
            "Communications link failure, NETWORK_UNREACHABLE",
            "connect timed out, NETWORK_UNREACHABLE",
            "java.net.SocketTimeoutException: Read timed out, NETWORK_UNREACHABLE",
            "java.net.UnknownHostException: db.internal, NETWORK_UNREACHABLE"
    })
    @DisplayName("errorCode=0 时按 message 关键词归类（如库不存在/库无权限/认证失败/拒绝/网络）")
    void should_classifyByMessageKeyword_when_errorCodeIsZero(String message,
                                                              ConnectionFailureCategory expected) {
        assertThat(ConnectionFailureClassifier.classify(ex("HY000", 0, message)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("message 关键词大小写不敏感，且 message 为 null 时不 NPE")
    void should_beCaseInsensitive_when_matchingMessage() {
        assertThat(ConnectionFailureClassifier.classify(ex("HY000", 0, "unknown database 'x'")))
                .isEqualTo(ConnectionFailureCategory.DATABASE_NOT_FOUND);
        assertThat(ConnectionFailureClassifier.classify(ex("HY000", 0, "COMMUNICATIONS LINK FAILURE")))
                .isEqualTo(ConnectionFailureCategory.NETWORK_UNREACHABLE);
        assertThat(ConnectionFailureClassifier.classify(ex("HY000", 0, null)))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
    }

    @Test
    @DisplayName("无法归类（如 ORA 报错/未知驱动异常）→ UNKNOWN 兜底，不抛异常")
    void should_returnUnknown_when_notClassifiable() {
        assertThat(ConnectionFailureClassifier.classify(ex("42000", 942, "ORA-00942: table or view does not exist")))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
        assertThat(ConnectionFailureClassifier.classify(new SQLException("unexpected failure")))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
    }

    @Test
    @DisplayName("null 异常 / null 与空白 SQLState / errorCode=0 / null message 均安全兜底，永不抛异常")
    void should_returnUnknown_when_inputDegenerate() {
        assertThat(ConnectionFailureClassifier.classify(null)).isEqualTo(ConnectionFailureCategory.UNKNOWN);
        assertThat(ConnectionFailureClassifier.classify(ex(null, 0, null)))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
        assertThat(ConnectionFailureClassifier.classify(ex("   ", 0, "   ")))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
        assertThatCode(() -> ConnectionFailureClassifier.classify(ex("", -1, "")))
                .doesNotThrowAnyException();
        assertThat(ConnectionFailureClassifier.classify(ex(null, -1, null)))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
    }

    @Test
    @DisplayName("errorCode 命中时即使 message 为 null 也可归类（不依赖 message）")
    void should_classifyByErrorCode_when_messageIsNull() {
        assertThat(ConnectionFailureClassifier.classify(ex("HY000", 1049, null)))
                .isEqualTo(ConnectionFailureCategory.DATABASE_NOT_FOUND);
        assertThat(ConnectionFailureClassifier.classify(ex(null, 1045, null)))
                .isEqualTo(ConnectionFailureCategory.AUTH_FAILED);
    }

    @Test
    @DisplayName("带 DbType 的重载与单参重载结果一致（含 null dbType）")
    void should_beConsistent_when_classifyWithDbType() {
        SQLException e = ex("08S01", 0, "Communications link failure");
        assertThat(ConnectionFailureClassifier.classify(e, DbType.OCEANBASE_MYSQL))
                .isEqualTo(ConnectionFailureClassifier.classify(e));
        assertThat(ConnectionFailureClassifier.classify(e, null))
                .isEqualTo(ConnectionFailureClassifier.classify(e));
        assertThat(ConnectionFailureClassifier.classify(null, DbType.MYSQL))
                .isEqualTo(ConnectionFailureCategory.UNKNOWN);
    }

    @Test
    @DisplayName("脱敏：类别中文/建议/组合文案为静态常量，不含 host/库名/账号/连接串原文")
    void should_notLeakSensitiveText_when_categoryTextAndSuggestion() {
        for (ConnectionFailureCategory category : ConnectionFailureCategory.values()) {
            assertThat(category.getText()).isNotBlank();
            assertThat(category.getSuggestion()).isNotBlank();
            assertThat(category.toErrorMessage())
                    .startsWith(category.getText() + "：")
                    .contains(category.getSuggestion())
                    .doesNotContain(SENSITIVE_HOST)
                    .doesNotContain(SENSITIVE_USER)
                    .doesNotContain(SENSITIVE_DB)
                    .doesNotContain(SENSITIVE_URL);
        }
    }

    @Test
    @DisplayName("脱敏：归类结果文案不携带驱动原始 message（凭据/地址不外泄）")
    void should_notEchoDriverMessage_when_classified() {
        String driverMessage = "Access denied for user '" + SENSITIVE_USER + "'@'" + SENSITIVE_HOST
                + "' to database '" + SENSITIVE_DB + "' (url=" + SENSITIVE_URL + ")";
        SQLException e = ex("HY000", 1045, driverMessage);
        ConnectionFailureCategory category = ConnectionFailureClassifier.classify(e);
        assertThat(category).isEqualTo(ConnectionFailureCategory.AUTH_FAILED);
        assertThat(category.toErrorMessage())
                .doesNotContain(SENSITIVE_HOST)
                .doesNotContain(SENSITIVE_USER)
                .doesNotContain(SENSITIVE_DB)
                .doesNotContain(SENSITIVE_URL)
                .doesNotContain(driverMessage);
    }

    @Test
    @DisplayName("枚举完整性：code 唯一且等于 name，类别码非空，含 UNKNOWN/CONFIG_UNRESOLVED 兜底与配置未就绪")
    void should_keepCodeUnique_when_enumConstants() {
        Set<String> codes = new HashSet<>();
        for (ConnectionFailureCategory category : ConnectionFailureCategory.values()) {
            assertThat(category.getCode()).isNotBlank().isEqualTo(category.name());
            assertThat(codes.add(category.getCode())).isTrue();
        }
        assertThat(codes).contains(
                "NETWORK_UNREACHABLE", "CONNECTION_REFUSED", "DATABASE_NOT_FOUND",
                "DATABASE_ACCESS_DENIED", "AUTH_FAILED", "CONFIG_UNRESOLVED", "UNKNOWN");
    }

    @Test
    @DisplayName("异常实例本身不被修改（无副作用）")
    void should_notMutateException_when_classify() {
        SQLException e = ex("08S01", 1045, "Communications link failure");
        ConnectionFailureClassifier.classify(e);
        assertThat(e.getSQLState()).isEqualTo("08S01");
        assertThat(e.getErrorCode()).isEqualTo(1045);
        assertThat(e.getMessage()).isEqualTo("Communications link failure");
    }
}
