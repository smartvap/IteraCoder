package com.agenthub.ai.datasource.vo;

import lombok.Data;

/**
 * 空间共享内部聚合视图（非 DELETED 数据源的 space_name→ds_id→manage_state 逐行原始数据）。
 *
 * <p>结构保持不变（零回归）：由 {@code DsSpaceSharingService} 在服务层按 space_name 分组，
 * 用于候选列表的共享计数/清单（同一空间可被多个数据源共享）。</p>
 */
@Data
public class DsBoundSpaceVO {

    private String dsId;

    private String spaceName;

    /** 管理状态（DRAFT/ACTIVE/DISABLED；已软删 DELETED 不参与共享统计） */
    private String manageState;
}
