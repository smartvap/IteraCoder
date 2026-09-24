package com.agenthub.ai.dbaccess.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OceanBaseAccountValidator 只读账号 {@code 用户@租户} 前置校验单测（FR-BE-010/011，data-model §3.4）。
 *
 * <p>覆盖：非 OB 族恒通过（零影响）、OB 族缺 {@code @} 不通过、{@code user@tenant} 与
 * {@code user@tenant#cluster} 通过、空白与 {@code ${ENV}} 占位形态、TIP 文案脱敏。</p>
 */
class OceanBaseAccountValidatorTest {

    /** 脱敏断言用样例账号（不得出现在提示文案中） */
    private static final String SENSITIVE_USER = "rca_reader_acct";

    @ParameterizedTest
    @CsvSource({
            "MYSQL, false",
            "mysql, false",
            "ORACLE, false",
            "oracle, false",
            "OCEANBASE_MYSQL, true",
            "oceanbase_mysql, true",
            "oceanbase-mysql, true",
            "OCEANBASE_ORACLE, true",
            "oceanbase_oracle, true"
    })
    @DisplayName("requiresTenant：仅 OceanBase 族（MySQL/Oracle 兼容模式）要求 @租户")
    void should_requireTenant_when_oceanBaseFamily(String dbType, boolean expected) {
        assertThat(OceanBaseAccountValidator.requiresTenant(dbType)).isEqualTo(expected);
    }

    @Test
    @DisplayName("requiresTenant：null / 空白 / 无法识别库类型均不要求（不抛异常）")
    void should_notRequireTenant_when_dbTypeUnresolvable() {
        assertThat(OceanBaseAccountValidator.requiresTenant(null)).isFalse();
        assertThat(OceanBaseAccountValidator.requiresTenant("")).isFalse();
        assertThat(OceanBaseAccountValidator.requiresTenant("   ")).isFalse();
        assertThat(OceanBaseAccountValidator.requiresTenant("sqlserver")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "MYSQL, ''",
            "MYSQL, reader",
            "ORACLE, reader",
            "ORACLE, ''"
    })
    @DisplayName("非 OB 族恒通过（含账号为空/无 @，零影响零阻断）")
    void should_pass_when_notOceanBaseFamily(String dbType, String user) {
        assertThat(OceanBaseAccountValidator.isValid(dbType, user)).isTrue();
    }

    @Test
    @DisplayName("非 OB 族/库类型不可解析：账号为 null 也恒通过")
    void should_pass_when_notOceanBaseFamilyAndUserNull() {
        assertThat(OceanBaseAccountValidator.isValid("MYSQL", null)).isTrue();
        assertThat(OceanBaseAccountValidator.isValid(null, null)).isTrue();
        assertThat(OceanBaseAccountValidator.isValid("sqlserver", null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader", "@sys", "reader@", "reader@#cluster", "  ", "@", "#cluster", "reader#cluster"})
    @DisplayName("OB 族：不含 @ / @ 前空 / @ 后空（含 #集群 前租户为空）均不通过")
    void should_fail_when_oceanBaseAccountFormatIllegal(String user) {
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", user)).isFalse();
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_ORACLE", user)).isFalse();
    }

    @Test
    @DisplayName("OB 族：账号为 null / 空白不通过（不抛异常）")
    void should_fail_when_oceanBaseAccountBlank() {
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", null)).isFalse();
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", "")).isFalse();
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", "   ")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "reader@sys",
            "reader@sys#cluster",
            "reader@sys#obcluster",
            "sys@sys",
            "  reader@sys  "
    })
    @DisplayName("OB 族：user@tenant（及 user@tenant#cluster）通过，首尾空白容忍")
    void should_pass_when_oceanBaseAccountFormatLegal(String user) {
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", user)).isTrue();
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_ORACLE", user)).isTrue();
    }

    @Test
    @DisplayName("${ENV} 占位形态：OB 族下无 @ 的占位不通过，@ 前后非空（含占位租户）通过；非 OB 族恒通过")
    void should_handleEnvPlaceholderForm() {
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", "${OB_USER}")).isFalse();
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", "${OB_USER}@${OB_TENANT}")).isTrue();
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", "reader@${OB_TENANT}")).isTrue();
        assertThat(OceanBaseAccountValidator.isValid("MYSQL", "${OB_USER}")).isTrue();
    }

    @Test
    @DisplayName("TIP 文案：非空、含 @ 格式提示，且不回显具体账号值")
    void should_notLeakAccountValue_when_tipText() {
        assertThat(OceanBaseAccountValidator.TIP).isNotBlank().contains("@");
        assertThat(OceanBaseAccountValidator.isValid("OCEANBASE_MYSQL", SENSITIVE_USER)).isFalse();
        assertThat(OceanBaseAccountValidator.TIP).doesNotContain(SENSITIVE_USER);
    }
}
