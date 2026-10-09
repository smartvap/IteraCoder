package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * SQL 模板检索召回响应 VO（接口21）。
 */
@Data
public class KbTemplateRetrievalVO {

    private List<KbTemplateVO> templates;
}
