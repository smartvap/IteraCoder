package com.agenthub.ai.rcagent.engine.vo;

import lombok.Data;

import java.util.List;

/**
 * RCAgent 候选源解析结果（api-contract.md §5.7）。
 *
 * <p>{@code POST /sources/resolve} 返回，且 {@code start} 内部复用同一解析结果组装 State，
 * 保证「预检」与「实际启动」同入参同结果。</p>
 */
@Data
public class RcaSourceResolveVO {

    /** 解析出的系统编码（可空） */
    private String systemCode;

    /** 主源（= dsIds[0]，满足恒等式 DS_ID == DS_IDS[0]） */
    private String primaryDsId;

    /** 候选源集合（有序、去重、主源在前） */
    private List<String> dsIds;

    /** 是否多源（dsIds.size() > 1） */
    private Boolean multiSource;

    /** 候选源数量 */
    private Integer candidateCount;

    /** 候选源明细 */
    private List<RcaSourceCandidateVO> sources;
}
