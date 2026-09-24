package com.agenthub.ai.datasource.vo;

import lombok.Data;

import java.util.List;

/**
 * 知识空间候选（api-contract §3 接口 5 SpaceOptionVO）。
 *
 * <p>语义变更：数据源 ↔ 知识空间由 1:1 放宽为 N:1 共享复用，所有远端候选空间均可被选用/复用，
 * 故 {@code available} **恒为 true**（字段保留仅为向前兼容存量前端）；新增
 * {@code boundDsCount}/{@code boundDsList}/{@code shareStatus}/{@code reuseRecommended} 表达共享情况。</p>
 */
@Data
public class SpaceOptionVO {

    /** 空间名 */
    private String name;

    /** 页面数 */
    private Integer pageCount;

    /** 集合名（snake_case） */
    private String collectionName;

    /** 是否可绑定：**恒 true**（所有空间均可绑定/复用；保留字段向前兼容） */
    private Boolean available;

    /** 兼容保留：已绑定时返回共享清单中按 ds_id 升序的第一个 dsId；无则 null */
    private String boundDs;

    /** 共享该空间的非 DELETED 数据源数量（0 表示尚未被任何数据源绑定） */
    private Integer boundDsCount;

    /** 共享该空间的数据源清单（空列表表示无；仅 dsId/dsName/manageState/systemCode，禁带连接/凭据） */
    private List<DsSpaceShareItemVO> boundDsList;

    /** 共享状态：FREE（0 源）/ EXCLUSIVE（1 源）/ SHARED（≥2 源） */
    private String shareStatus;

    /** 是否推荐复用：true 表示选择该空间即复用（boundDsCount ≥ 1） */
    private Boolean reuseRecommended;
}
