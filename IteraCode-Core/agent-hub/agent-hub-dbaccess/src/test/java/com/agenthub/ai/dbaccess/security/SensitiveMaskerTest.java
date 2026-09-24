package com.agenthub.ai.dbaccess.security;

import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveMasker 脱敏规则测试（对齐 AGENTS.md：手机号前3后4、证件首尾、HIGH 全掩码）。
 */
class SensitiveMaskerTest {

    @Test
    @DisplayName("手机号脱敏：保留前 3 后 4")
    void should_maskPhone_when_11DigitMobile() {
        assertThat(SensitiveMasker.mask("13812348000", SensitiveLevel.HIGH))
                .isEqualTo("138****8000");
    }

    @Test
    @DisplayName("18 位身份证脱敏：保留前 6 后 4")
    void should_maskIdCard18_when_fullIdNumber() {
        assertThat(SensitiveMasker.mask("110101199003077416", SensitiveLevel.HIGH))
                .isEqualTo("110101********7416");
    }

    @Test
    @DisplayName("15 位身份证脱敏：等长掩码（首 4 尾 4 实现，中间掩 7 位）")
    void should_maskIdCard15_when_legacyIdNumber() {
        assertThat(SensitiveMasker.mask("110101900307741", SensitiveLevel.HIGH))
                .isEqualTo("1101*******7741");
    }

    @Test
    @DisplayName("邮箱脱敏：保留首字符与域名")
    void should_maskEmail_when_hasDomain() {
        assertThat(SensitiveMasker.mask("alice@example.com", SensitiveLevel.MEDIUM))
                .isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("HIGH 无已知模式时全掩码，不泄露长度特征")
    void should_fullMask_when_highUnknownValue() {
        assertThat(SensitiveMasker.mask("SK-ABC-12345", SensitiveLevel.HIGH))
                .isEqualTo("******");
        assertThat(SensitiveMasker.mask("98765432101234567890", SensitiveLevel.HIGH))
                .isEqualTo("******");
    }

    @Test
    @DisplayName("MEDIUM 通用值保留首尾")
    void should_keepHeadTail_when_medium() {
        assertThat(SensitiveMasker.mask("张三丰", SensitiveLevel.MEDIUM)).isEqualTo("张*丰");
        assertThat(SensitiveMasker.mask("华东大区客户一部", SensitiveLevel.MEDIUM)).isEqualTo("华******部");
    }

    @Test
    @DisplayName("LOW 通用值保留首尾 1 位")
    void should_keepHeadTail_when_low() {
        assertThat(SensitiveMasker.mask("ABC", SensitiveLevel.LOW)).isEqualTo("A*C");
        assertThat(SensitiveMasker.mask("AB", SensitiveLevel.LOW)).isEqualTo("A*");
    }

    @Test
    @DisplayName("NONE 等级原样返回")
    void should_returnSame_when_noneLevel() {
        assertThat(SensitiveMasker.mask("plain-value", SensitiveLevel.NONE))
                .isEqualTo("plain-value");
    }

    @Test
    @DisplayName("null/空值原样返回")
    void should_returnSame_when_nullOrEmpty() {
        assertThat(SensitiveMasker.mask(null, SensitiveLevel.HIGH)).isNull();
        assertThat(SensitiveMasker.mask("", SensitiveLevel.HIGH)).isEmpty();
        assertThat(SensitiveMasker.mask("value", null)).isEqualTo("value");
    }

    @Test
    @DisplayName("手机号前后空格裁剪后再脱敏")
    void should_maskPhone_when_trimmed() {
        assertThat(SensitiveMasker.mask("  13812348000  ", SensitiveLevel.HIGH))
                .isEqualTo("138****8000");
    }

    @Test
    @DisplayName("maskSample 已知强敏感模式部分脱敏")
    void should_maskSample_when_knownPattern() {
        assertThat(SensitiveMasker.maskSample("13812348000")).isEqualTo("138****8000");
        assertThat(SensitiveMasker.maskSample("110101199003077416")).isEqualTo("110101********7416");
        assertThat(SensitiveMasker.maskSample("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("maskSample 未知格式强制全掩码，禁止原文回填（sampleValue 永不落原文）")
    void should_fullMaskSample_when_unknownFormat() {
        assertThat(SensitiveMasker.maskSample("zhangsan")).isEqualTo("******");
        assertThat(SensitiveMasker.maskSample("some-random-text")).isEqualTo("******");
        assertThat(SensitiveMasker.maskSample("")).isEmpty();
        assertThat(SensitiveMasker.maskSample(null)).isNull();
    }
}
