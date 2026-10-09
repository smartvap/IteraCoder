package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * 表关系检索召回响应 VO（接口23）。
 */
@Data
public class KbRelationRetrievalVO {

    private List<KbRelationVO> relations;
}
