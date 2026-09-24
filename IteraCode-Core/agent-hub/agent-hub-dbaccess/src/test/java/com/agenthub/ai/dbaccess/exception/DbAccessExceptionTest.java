package com.agenthub.ai.dbaccess.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DbAccessException 结构化异常测试（错误码携带与消息安全）。
 */
class DbAccessExceptionTest {

    @Test
    @DisplayName("通过枚举构造异常携带 code 与默认消息")
    void should_carryCode_when_constructByEnum() {
        DbAccessException ex = new DbAccessException(DbAccessErrorCode.WHITELIST_DENIED);
        assertThat(ex.getCode()).isEqualTo(1201);
        assertThat(ex.getMessage()).isEqualTo("白名单拒绝访问");
    }

    @Test
    @DisplayName("param 快捷工厂 code=1301")
    void should_paramFactory_when_invalid() {
        DbAccessException ex = DbAccessException.param("ds_id 不能为空");
        assertThat(ex.getCode()).isEqualTo(1301);
        assertThat(ex.getMessage()).isEqualTo("ds_id 不能为空");
    }

    @Test
    @DisplayName("system 快捷工厂 code=1500 且保留 cause")
    void should_systemFactory_when_unexpected() {
        IllegalStateException cause = new IllegalStateException("inner");
        DbAccessException ex = DbAccessException.system("SQL 执行失败", cause);
        assertThat(ex.getCode()).isEqualTo(1500);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("int 构造异常携带 code")
    void should_carryCode_when_constructByInt() {
        DbAccessException ex = new DbAccessException(999, "x");
        assertThat(ex.getCode()).isEqualTo(999);
    }
}
