package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * 语义知识检索召回响应 VO（接口24）。
 */
@Data
public class KbSemanticRetrievalVO {

    private List<KbMetricVO> metrics;

    private List<KbConceptVO> concepts;
}
