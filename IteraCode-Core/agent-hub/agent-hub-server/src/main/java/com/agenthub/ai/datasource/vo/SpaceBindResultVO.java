package com.agenthub.ai.datasource.vo;

import lombok.Data;

import java.util.List;

/**
 * 空间绑定/重试结果（api-contract §3 接口 4 SpaceBindResultVO）。
 *
 * <p>created=false 表示复用已有空间（**含复用被其他数据源持有的共享空间**）。</p>
 */
@Data
public class SpaceBindResultVO {

    private String dsId;

    /** 绑定空间名 */
    private String spaceName;

    /** BOUND/BIND_PENDING/BIND_FAILED */
    private String spaceBindState;

    /** 本次是否新建空间（false=复用已有，含共享复用） */
    private Boolean created;

    /** 降级/失败原因（脱敏） */
    private String error;

    /** 共享该空间的其他数据源 dsId 清单（脱敏，仅 dsId，按升序；BOUND 路径填充，降级路径可空） */
    private List<String> sharedDsList;

    /** 共享该空间的数据源总数（含自身；可空） */
    private Integer sharedDsCount;
}
