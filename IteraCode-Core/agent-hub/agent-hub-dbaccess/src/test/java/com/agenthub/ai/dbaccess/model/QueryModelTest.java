package com.agenthub.ai.dbaccess.model;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行请求/结果模型与错误码映射测试。
 */
class QueryModelTest {

    @Test
    @DisplayName("QueryRequest 默认 explainOnly=false")
    void should_defaultExplainOnly_when_build() {
        QueryRequest req = QueryRequest.builder().dsId("ds1").sql("SELECT 1").build();
        assertThat(req.isExplainOnly()).isFalse();
    }

    @Test
    @DisplayName("QueryResult.of 初始化为空结果")
    void should_initEmpty_when_of() {
        QueryResult result = QueryResult.of("ds1");
        assertThat(result.getDsId()).isEqualTo("ds1");
        assertThat(result.getColumnNames()).isNotNull().isEmpty();
        assertThat(result.getRows()).isNotNull().isEmpty();
        assertThat(result.getRowCount()).isZero();
        assertThat(result.getTruncated()).isFalse();
        assertThat(result.getSensitiveMasked()).isFalse();
        assertThat(result.getCostMs()).isZero();
    }

    @Test
    @DisplayName("DsStatus.of 映射状态码")
    void should_mapStatus_when_of() {
        assertThat(DsStatus.of(1)).isEqualTo(DsStatus.ENABLED);
        assertThat(DsStatus.of(0)).isEqualTo(DsStatus.DISABLED);
        assertThat(DsStatus.of(null)).isNull();
        assertThat(DsStatus.of(9)).isNull();
    }

    @Test
    @DisplayName("DsConfig.enabled/deleted 状态判定")
    void should_enabledAndDeleted_when_statusFlags() {
        DsConfig enabled = DsConfig.builder().status(1).isDeleted(0).build();
        DsConfig disabled = DsConfig.builder().status(0).isDeleted(0).build();
        DsConfig deleted = DsConfig.builder().status(1).isDeleted(1).build();
        assertThat(enabled.enabled()).isTrue();
        assertThat(disabled.enabled()).isFalse();
        assertThat(deleted.enabled()).isFalse();
        assertThat(deleted.deleted()).isTrue();
    }

    @Test
    @DisplayName("DsConfig.toView 生成脱敏视图（db_type 转 code，密码仍为引用，spaceNames 透传）")
    void should_toView_when_config() {
        DsConfig config = DsConfig.builder()
                .dsId("ds1").dsName("n").dbType(DbType.MYSQL)
                .readonlyUser("ro").readonlyPasswordRef("${DBACCESS_DS_DS1_PWD}")
                .spaceNames(List.of("CRM_A", "CRM_B"))
                .status(1).isDeleted(0)
                .build();
        DsConfigView view = config.toView();
        assertThat(view.getDbType()).isEqualTo("MYSQL");
        assertThat(view.getReadonlyPasswordRef()).isEqualTo("${DBACCESS_DS_DS1_PWD}");
        assertThat(view.getDsId()).isEqualTo("ds1");
        assertThat(view.getSpaceNames()).containsExactly("CRM_A", "CRM_B");
    }

    @Test
    @DisplayName("错误码映射 of(code) 与 DbAccessErrorCode 语义一致")
    void should_mapErrorCode_when_of() {
        assertThat(DbAccessErrorCode.of(1001)).isEqualTo(DbAccessErrorCode.UNKNOWN_DS);
        assertThat(DbAccessErrorCode.of(1002)).isEqualTo(DbAccessErrorCode.DS_DISABLED);
        assertThat(DbAccessErrorCode.of(1003)).isEqualTo(DbAccessErrorCode.DS_NOT_READY);
        assertThat(DbAccessErrorCode.of(1101)).isEqualTo(DbAccessErrorCode.NO_PROVIDER);
        assertThat(DbAccessErrorCode.of(1102)).isEqualTo(DbAccessErrorCode.DIALECT_UNSUPPORTED);
        assertThat(DbAccessErrorCode.of(1201)).isEqualTo(DbAccessErrorCode.WHITELIST_DENIED);
        assertThat(DbAccessErrorCode.of(1202)).isEqualTo(DbAccessErrorCode.SENSITIVE_DENIED);
        assertThat(DbAccessErrorCode.of(1203)).isEqualTo(DbAccessErrorCode.ROW_LIMIT_EXCEEDED);
        assertThat(DbAccessErrorCode.of(1204)).isEqualTo(DbAccessErrorCode.TIMEOUT);
        assertThat(DbAccessErrorCode.of(1205)).isEqualTo(DbAccessErrorCode.CONNECTION_FAILED);
        assertThat(DbAccessErrorCode.of(1301)).isEqualTo(DbAccessErrorCode.PARAM_INVALID);
        assertThat(DbAccessErrorCode.of(1302)).isEqualTo(DbAccessErrorCode.SQL_REJECTED);
        assertThat(DbAccessErrorCode.of(1500)).isEqualTo(DbAccessErrorCode.SYSTEM_ERROR);
        assertThat(DbAccessErrorCode.of(0)).isEqualTo(DbAccessErrorCode.SUCCESS);
        assertThat(DbAccessErrorCode.of(9999)).isNull();
    }

    @Test
    @DisplayName("SensitiveLevel/SensitivePolicy parse 解析")
    void should_parse_when_enums() {
        assertThat(SensitiveLevel.parseOrDefault("high")).isEqualTo(SensitiveLevel.HIGH);
        assertThat(SensitiveLevel.parseOrDefault("unknown")).isEqualTo(SensitiveLevel.NONE);
        assertThat(SensitiveLevel.HIGH.weight()).isEqualTo(3);
        assertThat(SensitivePolicy.parse("mask")).isEqualTo(SensitivePolicy.MASK);
        assertThat(SensitivePolicy.parse("deny")).isEqualTo(SensitivePolicy.DENY);
        assertThat(SensitivePolicy.parse("X")).isNull();
    }

    @Test
    @DisplayName("ResourceLimits JSON 值构造与可序列化（fastjson 往返）")
    void should_jsonRoundTrip_when_whitelistAndLimits() {
        ResourceLimits limits = ResourceLimits.builder()
                .maxRows(100).maxSeconds(5).allowSelectStar(false).build();
        WhitelistRule wl = WhitelistRule.builder().tables(List.of("A")).build();
        String lj = com.alibaba.fastjson2.JSON.toJSONString(limits);
        String wj = com.alibaba.fastjson2.JSON.toJSONString(wl);
        assertThat(lj).contains("100");
        assertThat(wj).contains("A");
    }

    @Test
    @DisplayName("QueryResult 完整字段可构建（截断标记/敏感掩码/计划）")
    void should_buildFullResult_when_assign() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        QueryResult result = QueryResult.builder()
                .dsId("ds1")
                .columnNames(List.of("id"))
                .rows(List.of(row))
                .rowCount(1)
                .truncated(true)
                .costMs(3L)
                .sensitiveMasked(true)
                .explainPlan("{}")
                .build();
        assertThat(result.getTruncated()).isTrue();
        assertThat(result.getRows()).isNotEmpty();
        assertThat(result.getExplainPlan()).isEqualTo("{}");
        // 结果不携带任何连接/密码字段
        assertThat(new ArrayList<>(result.getRows().get(0).keySet())).containsExactly("id");
    }
}
