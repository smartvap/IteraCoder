package com.agenthub.ai.rcagent.kbcrawler.model;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.ColumnTypeSignature;
import com.agenthub.ai.dbaccess.meta.IndexMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Schema 采集快照-逻辑模型（kb_crawl_schema_snapshot_view，不落库）。
 * 基于 module-002 MetadataProvider.listTables/listColumns 的中立元数据组装，
 * 叠加 Mapper 解析字段注释补充与统计脱敏。
 */
@Data
public class SchemaSnapshotVO {

    /** 数据源 */
    private String dsId;

    /** 库类型（DbType 枚举 name，如 MYSQL/ORACLE/OCEANBASE_MYSQL） */
    private String dbType;

    /** Schema/库名 */
    private String spaceName;

    /** 表元数据（中立模型） */
    private List<TableMeta> tables;

    /** 表级指纹缓存：表名 → 采集快照指纹（供 schema 落库 + 变更检测复用） */
    private List<TableFingerprint> fingerprints;

    @Data
    public static class TableFingerprint {
        /** 表名 */
        private String tableName;

        /** 表类型：TABLE/VIEW */
        private String tableType;

        /** 列定义指纹（列名+类型排序哈希） */
        private String columnsHash;

        /** 行数估计（脱敏聚合值） */
        private Long rowCountEstimate;

        /** 列名集合（供列级 diff） */
        private List<String> columnNames;

        /** 列名→类型映射快照（供 column type change 识别，JSON 文本缓存于快照表） */
        private String columnsJson;

        /** 采样补充（列名→注释/敏感级别等，脱敏） */
        private String metaJson;
    }

    /** 生成表的 columnsHash（供指纹统一）
     *
     * <p>列指纹元素口径：{@code 列名 + ":" + ColumnTypeSignature.signature(c)}，
     * 与字典落库 {@code kb_column.data_type} 共用同一签名实现，保证「检测口径」与「落库口径」不漂移。
     * 排序逻辑/hashHex/substring(0,32)/空列行为与改造前一致。</p>
     */
    public static String columnsHashOf(TableMeta table) {
        StringBuilder sb = new StringBuilder();
        if (table.getColumns() != null) {
            List<ColumnMeta> cols = table.getColumns();
            cols.sort((a, b) -> {
                if (a.getColumnName() == null) {
                    return b.getColumnName() == null ? 0 : -1;
                }
                return a.getColumnName().compareTo(b.getColumnName());
            });
            for (ColumnMeta c : cols) {
                sb.append(c.getColumnName()).append(':').append(ColumnTypeSignature.signature(c)).append(';');
            }
        }
        return hashHex(sb.toString()).substring(0, 32);
    }

    /** 生成表的索引指纹（供采集侧「仅索引变化」增量判定与变更检测侧 INDEX_CHANGE 识别）
     *
     * <p><b>与列指纹 {@link #columnsHashOf(TableMeta)} 完全解耦</b>：本方法结果独立存储于
     * {@code table_meta_json.indexHash}，绝不参与 {@code columns_hash}。否则升级首轮会把全部表误判为
     * 结构变更，误触发引用模板/链路置信度 ×0.5 降级（护栏 G-04）。</p>
     *
     * <p>元素口径：每索引 {@code indexName + ":" + isUnique + ":" + isPrimary + ":" + String.join(",", columns)}，
     * 按 {@code indexName} 字典序排序后以 {@code ;} 连接；无索引时参与哈希的输入为空串（仍返回定长 32 位摘要，
     * 与 columns_hash 长度口径一致）。null 安全，且不就地排序改动入参集合顺序。</p>
     */
    public static String indexHashOf(TableMeta table) {
        StringBuilder sb = new StringBuilder();
        if (table != null && table.getIndexes() != null && !table.getIndexes().isEmpty()) {
            // 复制后排序：避免像 columnsHashOf 那样直接改动调用方（Provider 采集产物）的集合顺序
            List<IndexMeta> indexes = new ArrayList<>(table.getIndexes());
            indexes.sort(Comparator.comparing(i -> i.getIndexName() == null ? "" : i.getIndexName()));
            for (IndexMeta i : indexes) {
                List<String> cols = i.getColumns() == null ? List.of() : i.getColumns();
                sb.append(i.getIndexName()).append(':')
                        .append(i.getIsUnique()).append(':')
                        .append(i.getIsPrimary()).append(':')
                        .append(String.join(",", cols)).append(';');
            }
        }
        return hashHex(sb.toString()).substring(0, 32);
    }

    /** SHA-256 简写（前 32 位，与 data-model columns_hash 长度一致） */
    public static String hashHex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }
}
