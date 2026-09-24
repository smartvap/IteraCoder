package com.agenthub.ai.datasource.vo;

import lombok.Data;

import java.util.List;

/**
 * 知识空间共享分组视图（api-contract §4.4 / data-model §3.1 V-01）。
 *
 * <p>由 {@code DsSpaceSharingService} 在服务层按 {@code space_name} 内存分组派生（不落库、无 DDL）。
 * 供候选列表聚合与共享提示复用。</p>
 */
@Data
public class DsSpaceShareVO {

    /** 空间名（与 llm-wiki 远端空间名精确匹配） */
    private String spaceName;

    /** 共享该空间的非 DELETED 数据源数量（0 表示尚未被任何数据源持有） */
    private Integer boundDsCount;

    /** 其中 manage_state = ACTIVE 的数量 */
    private Integer activeDsCount;

    /** 共享清单（含自身，按 dsId 升序稳定输出；仅非敏感字段） */
    private List<DsSpaceShareItemVO> boundDsList;

    /** 共享状态：FREE（0 个源）/ EXCLUSIVE（1 个源）/ SHARED（≥2 个源） */
    private String shareStatus;
}
