package com.agenthub.ai.dbaccess.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 元数据采集请求（api-contract MetadataRequest）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataRequest {

    /** 数据源标识（必填） */
    private String dsId;

    /** 覆盖空间/Schema（可空，默认用 ds 配置） */
    private String spaceName;

    /** 是否连表带列（默认 true） */
    @Builder.Default
    private Boolean includeColumns = Boolean.TRUE;

    /** 是否采集行数/采样（默认 false，避免重查询） */
    @Builder.Default
    private Boolean includeStats = Boolean.FALSE;

    /** 是否采集列级外键标记（默认 false，零回归闸门：false 时不查询、不改变既有行为） */
    @Builder.Default
    private Boolean includeForeignKeys = Boolean.FALSE;

    /** 是否采集索引并回填 TableMeta.indexes（默认 false，零回归闸门） */
    @Builder.Default
    private Boolean includeIndexes = Boolean.FALSE;

    public boolean includeColumns() {
        return !Boolean.FALSE.equals(includeColumns);
    }

    public boolean includeStats() {
        return Boolean.TRUE.equals(includeStats);
    }

    public boolean includeForeignKeys() {
        return Boolean.TRUE.equals(includeForeignKeys);
    }

    public boolean includeIndexes() {
        return Boolean.TRUE.equals(includeIndexes);
    }
}
