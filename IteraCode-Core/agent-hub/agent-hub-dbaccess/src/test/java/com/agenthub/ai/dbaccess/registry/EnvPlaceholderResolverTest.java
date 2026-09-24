package com.agenthub.ai.dbaccess.registry;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EnvPlaceholderResolver ${ENV} 密码引用解析测试（凭据脱敏、缺失即失败）。
 */
class EnvPlaceholderResolverTest {

    private final Environment environment = mock(Environment.class);

    private EnvPlaceholderResolver resolver() {
        return new EnvPlaceholderResolver(environment);
    }

    @Test
    @DisplayName("合法 ${ENV} 引用解析出环境变量值（仅进程内使用）")
    void should_resolve_when_validRef() {
        when(environment.getProperty("DBACCESS_TEST_PWD")).thenReturn("dummy-pwd");
        EnvPlaceholderResolver resolver = resolver();
        assertThat(resolver.resolvePasswordRef("${DBACCESS_TEST_PWD}")).isEqualTo("dummy-pwd");
    }

    @Test
    @DisplayName("环境变量缺失抛 DS_NOT_READY 且 message 不含变量名")
    void should_throwNotReady_when_envMissing() {
        when(environment.getProperty("DBACCESS_MISSING_PWD")).thenReturn(null);
        EnvPlaceholderResolver resolver = resolver();
        assertThatThrownBy(() -> resolver.resolvePasswordRef("${DBACCESS_MISSING_PWD}"))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> {
                    DbAccessException ex = (DbAccessException) e;
                    assertThat(ex.getCode()).isEqualTo(DbAccessErrorCode.DS_NOT_READY.getCode());
                    assertThat(ex.getMessage()).doesNotContain("DBACCESS_MISSING_PWD");
                });
    }

    @Test
    @DisplayName("明文/非 ${ENV} 引用抛 PARAM_INVALID")
    void should_reject_when_notRef() {
        when(environment.getProperty("X")).thenReturn(null);
        EnvPlaceholderResolver resolver = resolver();
        assertThatThrownBy(() -> resolver.resolvePasswordRef("clear-text-pwd"))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> resolver.resolvePasswordRef(null))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.DS_NOT_READY.getCode()));
        assertThatThrownBy(() -> resolver.resolvePasswordRef("  "))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("resolveAll 替换文本内多个占位")
    void should_resolveAll_when_placeholders() {
        when(environment.getProperty("HOST")).thenReturn("localhost");
        when(environment.getProperty("DB")).thenReturn("testdb");
        EnvPlaceholderResolver resolver = resolver();
        String url = resolver.resolveAll("jdbc:mysql://${HOST}:3306/${DB}");
        assertThat(url).isEqualTo("jdbc:mysql://localhost:3306/testdb");
    }
}
