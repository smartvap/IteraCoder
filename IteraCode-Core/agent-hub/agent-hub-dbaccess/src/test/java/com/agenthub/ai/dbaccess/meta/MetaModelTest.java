package com.agenthub.ai.dbaccess.meta;

import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中立元数据模型（TableMeta/ColumnMeta/RelationMeta/MetadataRequest）测试。
 */
class MetaModelTest {

    @Test
    @DisplayName("TableMeta 中立字段可构建且列清单默认空集合")
    void should_buildTableMeta_when_providerAssemble() {
        ColumnMeta col = ColumnMeta.builder()
                .tableName("CUSTOMER")
                .columnName("PHONE")
                .ordinal(2)
                .dataType("VARCHAR")
                .columnSize(20)
                .nullable(1)
                .isPrimaryKey(0)
                .isForeignKey(0)
                .columnComment("手机号")
                .sensitiveLevel(SensitiveLevel.HIGH)
                .sampleValue("138****8000")
                .build();
        TableMeta table = TableMeta.builder()
                .tableName("CUSTOMER")
                .spaceName("testdb")
                .dbType(DbType.MYSQL)
                .tableComment("客户表")
                .tableType("TABLE")
                .rowCountEstimate(1000L)
                .provider("mysql")
                .columns(List.of(col))
                .build();

        assertThat(table.getTableName()).isEqualTo("CUSTOMER");
        assertThat(table.getSpaceName()).isEqualTo("testdb");
        assertThat(table.getDbType()).isEqualTo(DbType.MYSQL);
        assertThat(table.getProvider()).isEqualTo("mysql");
        assertThat(table.getColumns()).hasSize(1);
        assertThat(table.getColumns().get(0).getSensitiveLevel()).isEqualTo(SensitiveLevel.HIGH);
        // 采样值必须已脱敏
        assertThat(table.getColumns().get(0).getSampleValue()).isNotEqualTo("13812348000");
    }

    @Test
    @DisplayName("TableMeta builder 默认 columns 为空集合且不为 null")
    void should_defaultColumnsEmpty_when_notSet() {
        TableMeta table = TableMeta.builder().tableName("T").build();
        assertThat(table.getColumns()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("ColumnMeta 敏感等级默认 NONE")
    void should_defaultSensitiveLevelNone_when_notSet() {
        ColumnMeta col = ColumnMeta.builder().columnName("ID").build();
        assertThat(col.getSensitiveLevel()).isEqualTo(SensitiveLevel.NONE);
        assertThat(col.getSensitiveLevel().weight()).isZero();
    }

    @Test
    @DisplayName("RelationMeta 关系元数据字段完整")
    void should_buildRelationMeta_when_foreignKey() {
        RelationMeta relation = RelationMeta.builder()
                .sourceTable("ORDERS")
                .sourceColumn("CUSTOMER_ID")
                .targetTable("CUSTOMER")
                .targetColumn("ID")
                .relationType("FK")
                .constraintName("fk_order_customer")
                .build();

        assertThat(relation.getSourceTable()).isEqualTo("ORDERS");
        assertThat(relation.getTargetTable()).isEqualTo("CUSTOMER");
        assertThat(relation.getRelationType()).isEqualTo("FK");
    }

    @Test
    @DisplayName("MetadataRequest 默认 includeColumns=true、includeStats=false")
    void should_defaultFlags_when_notSet() {
        MetadataRequest req = MetadataRequest.builder().dsId("ds1").build();
        assertThat(req.includeColumns()).isTrue();
        assertThat(req.includeStats()).isFalse();
        assertThat(req.getDsId()).isEqualTo("ds1");
    }

    @Test
    @DisplayName("MetadataRequest 显式设置 includeColumns=false 生效")
    void should_disableColumns_when_explicitFalse() {
        MetadataRequest req = MetadataRequest.builder()
                .dsId("ds1")
                .includeColumns(false)
                .build();
        assertThat(req.includeColumns()).isFalse();
    }

    @Test
    @DisplayName("MetadataRequest includeStats=true 生效")
    void should_enableStats_when_explicitTrue() {
        MetadataRequest req = MetadataRequest.builder()
                .dsId("ds1")
                .spaceName("s1")
                .includeStats(true)
                .build();
        assertThat(req.includeStats()).isTrue();
    }
}
