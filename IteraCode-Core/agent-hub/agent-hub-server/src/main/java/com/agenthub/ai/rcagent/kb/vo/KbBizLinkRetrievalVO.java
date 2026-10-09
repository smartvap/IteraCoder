package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * 业务链路检索召回响应 VO（接口22）。
 */
@Data
public class KbBizLinkRetrievalVO {

    private List<KbBizLinkVO> links;
}
