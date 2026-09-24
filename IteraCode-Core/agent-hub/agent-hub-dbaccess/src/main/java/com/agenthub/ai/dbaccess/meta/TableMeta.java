package com.agenthub.ai.dbaccess.meta;

import com.agenthub.ai.dbaccess.model.DbType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 中立表元数据（data-model.md t_meta_table，Provider 采集产物，不落库）。
 *
 * <p>所有字段与具体库类型无关，方言差异在 Provider/Dialect 层吸收。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableMeta {

    /** 表名 */
    private String tableName;

    /** Schema/Space 名 */
    private String spaceName;

    /** 来源库类型 */
    private DbType dbType;

    /** 表注释 */
    private String tableComment;

    /** TABLE / VIEW */
    private String tableType;

    /** 采样行数估计（脱敏采样，可空） */
    private Long rowCountEstimate;

    /** 采集 Provider 标识（mysql/oracle/oceanbase_mysql/fallback） */
    private String provider;

    /** 列元数据列表（按 includeColumns 装配，可为空集合） */
    @Builder.Default
    private List<ColumnMeta> columns = new ArrayList<>();

    /** 索引元数据列表（按 includeIndexes 装配，includeIndexes=false 时为空集合，不为 null） */
    @Builder.Default
    private List<IndexMeta> indexes = new ArrayList<>();
}
