package com.agenthub.ai.rcagent.engine.vo;

import lombok.Data;

import java.util.List;

/**
 * RCAgent 系统分组视图（api-contract.md §5.5）。
 *
 * <p>「系统」由 {@code t_ds_config.system_code} 运行时分组派生（非落库），
 * 供前端渲染系统下拉并预览候选源集合。</p>
 */
@Data
public class RcaSourceGroupVO {

    /** 系统编码；「未分组」时为 null */
    private String systemCode;

    /** 展示名（等于 systemCode，未分组时为「未分组」） */
    private String systemName;

    /** 候选源数量 */
    private Integer candidateCount;

    /** 候选源 dsId 列表（按 ds_id 升序） */
    private List<String> candidateDsIds;

    /** 候选源明细 */
    private List<RcaSourceCandidateVO> sources;
}
