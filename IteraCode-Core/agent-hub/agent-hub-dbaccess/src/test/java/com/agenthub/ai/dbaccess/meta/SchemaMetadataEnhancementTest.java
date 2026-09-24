package com.agenthub.ai.dbaccess.meta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema-metadata-enhancement（T001）产出模型最小必要验证：
 * <ul>
 *   <li>{@link MetadataRequest} 新增闸门 {@code includeForeignKeys}/{@code includeIndexes} 默认关闭；</li>
 *   <li>{@link TableMeta} 新增 {@code indexes} 默认非 null 空集合，显式设置后有序保留；</li>
 *   <li>{@link IndexMeta} 构建与字段读写正常。</li>
 * </ul>
 *
 * <p>纯 POJO 单元测试，不依赖真实数据库、不启动 Spring 容器。</p>
 */
class SchemaMetadataEnhancementTest {

    @Nested
    @DisplayName("MetadataRequest：新增采集闸门默认关闭（零回归）")
    class MetadataRequestToggles {

        @Test
        @DisplayName("builder().build() 时 includeForeignKeys()/includeIndexes() 均返回 false")
        void should_defaultBothTogglesFalse_when_builderBuild() {
            MetadataRequest req = MetadataRequest.builder().build();

            assertThat(req.includeForeignKeys()).isFalse();
            assertThat(req.includeIndexes()).isFalse();
        }

        @Test
        @DisplayName("默认构造时 getter 字段为 Boolean.FALSE 且语义开关为 false")
        void should_defaultFieldsFalse_when_noArgs() {
            MetadataRequest req = new MetadataRequest();

            assertThat(req.getIncludeForeignKeys()).isEqualTo(Boolean.FALSE);
            assertThat(req.getIncludeIndexes()).isEqualTo(Boolean.FALSE);
            assertThat(req.includeForeignKeys()).isFalse();
            assertThat(req.includeIndexes()).isFalse();
        }

        @Test
        @DisplayName("显式 true 时语义开关为 true")
        void should_enableToggles_when_explicitTrue() {
            MetadataRequest req = MetadataRequest.builder()
                    .dsId("ds1")
                    .includeForeignKeys(true)
                    .includeIndexes(true)
                    .build();

            assertThat(req.includeForeignKeys()).isTrue();
            assertThat(req.includeIndexes()).isTrue();
        }

        @Test
        @DisplayName("显式 false 时语义开关为 false")
        void should_disableToggles_when_explicitFalse() {
            MetadataRequest req = MetadataRequest.builder()
                    .includeForeignKeys(false)
                    .includeIndexes(false)
                    .build();

            assertThat(req.includeForeignKeys()).isFalse();
            assertThat(req.includeIndexes()).isFalse();
        }

        @Test
        @DisplayName("getter 被置为 null 时语义开关安全返回 false（无 NPE）")
        void should_returnFalse_when_fieldNull() {
            MetadataRequest req = new MetadataRequest();
            req.setIncludeForeignKeys(null);
            req.setIncludeIndexes(null);

            assertThat(req.includeForeignKeys()).isFalse();
            assertThat(req.includeIndexes()).isFalse();
        }
    }

    @Nested
    @DisplayName("TableMeta：indexes 默认空集合与有序保留")
    class TableMetaIndexes {

        @Test
        @DisplayName("builder 默认 indexes 为非 null 空集合")
        void should_defaultIndexesEmpty_when_notSet() {
            TableMeta table = TableMeta.builder().tableName("T").build();

            assertThat(table.getIndexes()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("无参构造默认 indexes 为非 null 空集合")
        void should_defaultIndexesEmpty_when_noArgs() {
            TableMeta table = new TableMeta();

            assertThat(table.getIndexes()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("显式 builder 设置 indexes 后按添加顺序保留")
        void should_preserveOrder_when_indexesExplicit() {
            IndexMeta uk = IndexMeta.builder()
                    .spaceName("testdb")
                    .tableName("CUSTOMER")
                    .indexName("uk_customer_phone")
                    .columns(List.of("PHONE"))
                    .isUnique(1)
                    .isPrimary(0)
                    .build();
            IndexMeta pk = IndexMeta.builder()
                    .spaceName("testdb")
                    .tableName("CUSTOMER")
                    .indexName("PRIMARY")
                    .columns(List.of("ID"))
                    .isUnique(1)
                    .isPrimary(1)
                    .build();

            TableMeta table = TableMeta.builder()
                    .tableName("CUSTOMER")
                    .indexes(List.of(uk, pk))
                    .build();

            assertThat(table.getIndexes()).hasSize(2);
            assertThat(table.getIndexes()).extracting(IndexMeta::getIndexName)
                    .containsExactly("uk_customer_phone", "PRIMARY");
        }

        @Test
        @DisplayName("indexes 可通过 setter 回填（采集编排写入路径）")
        void should_fillIndexes_when_setterUsed() {
            TableMeta table = TableMeta.builder().tableName("CUSTOMER").build();
            assertThat(table.getIndexes()).isEmpty();

            List<IndexMeta> collected = new ArrayList<>();
            collected.add(IndexMeta.builder().indexName("idx_a").build());
            table.setIndexes(collected);

            assertThat(table.getIndexes()).hasSize(1);
            assertThat(table.getIndexes().get(0).getIndexName()).isEqualTo("idx_a");
        }
    }

    @Nested
    @DisplayName("IndexMeta：构建与字段读写")
    class IndexMetaModel {

        @Test
        @DisplayName("builder 构建全部字段可正确读出")
        void should_buildAndReadAllFields() {
            IndexMeta idx = IndexMeta.builder()
                    .spaceName("testdb")
                    .tableName("ORDERS")
                    .indexName("idx_orders_customer")
                    .columns(List.of("CUSTOMER_ID", "CREATED_AT"))
                    .isUnique(0)
                    .isPrimary(0)
                    .indexType("BTREE")
                    .build();

            assertThat(idx.getSpaceName()).isEqualTo("testdb");
            assertThat(idx.getTableName()).isEqualTo("ORDERS");
            assertThat(idx.getIndexName()).isEqualTo("idx_orders_customer");
            assertThat(idx.getColumns()).containsExactly("CUSTOMER_ID", "CREATED_AT");
            assertThat(idx.getIsUnique()).isZero();
            assertThat(idx.getIsPrimary()).isZero();
            assertThat(idx.getIndexType()).isEqualTo("BTREE");
        }

        @Test
        @DisplayName("无参构造 + setter 可正常写入并读出")
        void should_readWrite_when_settersUsed() {
            IndexMeta idx = new IndexMeta();
            idx.setSpaceName("testdb");
            idx.setTableName("CUSTOMER");
            idx.setIndexName("PRIMARY");
            idx.setColumns(List.of("ID"));
            idx.setIsUnique(1);
            idx.setIsPrimary(1);
            idx.setIndexType(null);

            assertThat(idx.getSpaceName()).isEqualTo("testdb");
            assertThat(idx.getTableName()).isEqualTo("CUSTOMER");
            assertThat(idx.getIndexName()).isEqualTo("PRIMARY");
            assertThat(idx.getColumns()).containsExactly("ID");
            assertThat(idx.getIsUnique()).isEqualTo(1);
            assertThat(idx.getIsPrimary()).isEqualTo(1);
            assertThat(idx.getIndexType()).isNull();
        }

        @Test
        @DisplayName("全参构造可正确赋值")
        void should_assign_when_allArgsConstructor() {
            IndexMeta idx = new IndexMeta(
                    "testdb", "CUSTOMER", "uk_phone", List.of("PHONE"), 1, 0, "HASH");

            assertThat(idx.getSpaceName()).isEqualTo("testdb");
            assertThat(idx.getTableName()).isEqualTo("CUSTOMER");
            assertThat(idx.getIndexName()).isEqualTo("uk_phone");
            assertThat(idx.getColumns()).containsExactly("PHONE");
            assertThat(idx.getIsUnique()).isEqualTo(1);
            assertThat(idx.getIsPrimary()).isZero();
            assertThat(idx.getIndexType()).isEqualTo("HASH");
        }

        @Test
        @DisplayName("equals/hashCode 基于字段值一致")
        void should_beEqual_when_sameFields() {
            IndexMeta a = IndexMeta.builder().indexName("PRIMARY").columns(List.of("ID")).build();
            IndexMeta b = IndexMeta.builder().indexName("PRIMARY").columns(List.of("ID")).build();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }
    }
}
