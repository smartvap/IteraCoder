package com.agenthub.ai.dbaccess.meta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ColumnTypeSignature} 单测：严格覆盖 data-model.md §6「类型签名规则」与「示例（等价性自检表）」全部行，
 * 以及「签名补全等价判定 {@code isSignatureBackfill}」真值表。
 *
 * <p>断言与文档一一对应，不放松、不近似。</p>
 */
class ColumnTypeSignatureTest {

    private static ColumnMeta column(String dataType, Integer columnSize, Integer numericPrecision, Integer numericScale) {
        return ColumnMeta.builder()
                .tableName("T")
                .columnName("C")
                .dataType(dataType)
                .columnSize(columnSize)
                .numericPrecision(numericPrecision)
                .numericScale(numericScale)
                .build();
    }

    // ------------------------------------------------------------------
    // signature(ColumnMeta)：示例自检表 + 规则边界
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("signature：示例自检表（data-model.md §6）与规则边界")
    class SignatureRules {

        static Stream<Arguments> signatureExamples() {
            return Stream.of(
                    // ---- 文档示例自检表（逐行） ----
                    Arguments.of("varchar", 64, null, null, "varchar(64)"),
                    Arguments.of("varchar(32)", null, null, null, "varchar(32)"),
                    Arguments.of("decimal", null, 10, 2, "decimal(10,2)"),
                    Arguments.of("decimal", null, 12, 0, "decimal(12,0)"),
                    Arguments.of("number", null, 10, null, "number(10,0)"),
                    Arguments.of("bigint", 20, 19, 0, "bigint"),
                    Arguments.of("DATETIME", null, null, null, "datetime"),
                    Arguments.of("int", null, null, null, "int"),
                    // ---- 任务补充边界 ----
                    Arguments.of("varchar", 32, null, null, "varchar(32)"),          // varchar/32
                    Arguments.of("varchar", 0, null, null, "varchar"),               // columnSize=0 → 不拼长度
                    Arguments.of("char", 1, null, null, "char(1)"),
                    Arguments.of("nvarchar2", 128, null, null, "nvarchar2(128)"),
                    Arguments.of("varbinary", 16, null, null, "varbinary(16)"),
                    Arguments.of("binary", 8, null, null, "binary(8)"),
                    Arguments.of("VARCHAR", null, null, null, "varchar"),            // 仅类型名（小写归一）
                    Arguments.of("varchar", null, null, null, "varchar"),            // columnSize 缺失
                    Arguments.of("number", null, null, null, "number"),              // 数值族但无精度 → 裸类型名
                    Arguments.of("float", null, 10, null, "float(10,0)"),            // 数值族 + 标度缺省 0
                    Arguments.of("numeric", null, 10, 2, "numeric(10,2)"),
                    Arguments.of("dec", null, 8, 3, "dec(8,3)"),
                    Arguments.of("varchar", 10, 99, 9, "varchar(10)"),               // 长度族：只拼 length，忽略 numeric*
                    Arguments.of("decimal", 10, null, null, "decimal"),              // 数值族但精度缺失 → 裸类型名
                    Arguments.of("  varchar  ", 64, null, null, "varchar(64)"),      // trim + 去内部空白
                    Arguments.of("BIGINT", null, 19, 0, "bigint")                    // 整数族忽略精度/标度
            );
        }

        @ParameterizedTest(name = "[{index}] {0}/{1}/{2}/{3} -> {4}")
        @MethodSource("signatureExamples")
        @DisplayName("signature：示例自检表全部行 + 边界组合")
        void should_composeSignature_when_examples(String dataType, Integer size,
                                                   Integer precision, Integer scale, String expected) {
            assertThat(ColumnTypeSignature.signature(column(dataType, size, precision, scale)))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("signature：column == null → 返回空串")
        void should_returnEmpty_when_columnNull() {
            assertThat(ColumnTypeSignature.signature(null)).isEmpty();
        }

        @Test
        @DisplayName("signature：dataType 为空串/空白 → 返回空串")
        void should_returnEmpty_when_dataTypeBlank() {
            assertThat(ColumnTypeSignature.signature(column(null, 64, 10, 2))).isEmpty();
            assertThat(ColumnTypeSignature.signature(column("", 64, 10, 2))).isEmpty();
            assertThat(ColumnTypeSignature.signature(column("   ", 64, 10, 2))).isEmpty();
        }

        @Test
        @DisplayName("signature：dataType 已含 '(' → 原样返回（仅 trim + 小写，禁止二次拼装）")
        void should_returnAsIs_when_dataTypeAlreadyParameterized() {
            assertThat(ColumnTypeSignature.signature(column("VARCHAR(32)", null, null, null)))
                    .isEqualTo("varchar(32)");
            assertThat(ColumnTypeSignature.signature(column("DECIMAL(10,2)", null, null, null)))
                    .isEqualTo("decimal(10,2)");
            // 已带参数时即使同时提供 numeric* / columnSize 也不二次拼装
            assertThat(ColumnTypeSignature.signature(column("varchar(64)", 999, 99, 9)))
                    .isEqualTo("varchar(64)");
        }

        @Test
        @DisplayName("signature：纯函数（同输入多次调用结果一致，不修改入参）")
        void should_bePure_when_calledTwice() {
            ColumnMeta col = column("decimal", null, 10, 2);
            String first = ColumnTypeSignature.signature(col);
            String second = ColumnTypeSignature.signature(col);
            assertThat(first).isEqualTo("decimal(10,2)").isEqualTo(second);
            // 入参字段未被修改
            assertThat(col.getDataType()).isEqualTo("decimal");
            assertThat(col.getNumericPrecision()).isEqualTo(10);
            assertThat(col.getNumericScale()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------
    // isSignatureBackfill：真值表
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("isSignatureBackfill：真值表")
    class Backfill {

        static Stream<Arguments> backfillCases() {
            return Stream.of(
                    // ---- 等价（无变化 / 同值）----
                    Arguments.of("varchar", "varchar", true),
                    Arguments.of("varchar(64)", "varchar(64)", true),
                    Arguments.of("decimal(10,2)", "decimal(10,2)", true),
                    Arguments.of("", "", true),
                    Arguments.of(null, null, true),
                    // ---- 签名补全等价（旧=裸类型名，新=同基类型签名）----
                    Arguments.of("varchar", "varchar(64)", true),                    // spec 验收场景 5
                    Arguments.of("VARCHAR", "varchar(64)", true),                    // 大小写归一
                    Arguments.of("varchar ", " varchar(64) ", true),                 // 空白容忍
                    Arguments.of("decimal", "decimal(10,2)", true),
                    Arguments.of("number", "number(10,0)", true),
                    // ---- 长度/精度变化 → 真实结构变更 ----
                    Arguments.of("varchar(64)", "varchar(128)", false),              // spec 验收场景 5
                    Arguments.of("decimal(10,2)", "decimal(12,4)", false),           // spec 验收场景 3
                    Arguments.of("number(10,0)", "number(10,2)", false),
                    // ---- 基类型变化 ----
                    Arguments.of("int", "bigint", false),                            // spec 验收场景 5
                    Arguments.of("varchar", "int(11)", false),
                    Arguments.of("varchar(64)", "int", false),
                    Arguments.of("date", "datetime", false),
                    // ---- null / 空 组合 ----
                    Arguments.of(null, "varchar(64)", false),
                    Arguments.of("varchar", null, false),
                    Arguments.of("", "varchar(64)", false),
                    Arguments.of("varchar", "", false),
                    // ---- 大小写不同但均已带参数：不满足「旧值无 '('」前置 → 判为变化 ----
                    Arguments.of("VARCHAR(64)", "varchar(64)", false)
            );
        }

        @ParameterizedTest(name = "[{index}] old={0}, new={1} -> {2}")
        @MethodSource("backfillCases")
        @DisplayName("isSignatureBackfill：等价 / 长度变化 / 类型变化 / null 组合")
        void should_decideBackfill(String oldType, String newType, boolean expected) {
            assertThat(ColumnTypeSignature.isSignatureBackfill(oldType, newType)).isEqualTo(expected);
        }
    }
}
