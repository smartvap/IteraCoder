package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

/**
 * 知识详情响应 VO（接口10）。
 */
@Data
public class KbDetailVO {

    /** 对象类型 */
    private String objectType;

    /** 对象 id */
    private Long objectId;

    /** 完整对象字段（对应实体） */
    private Object content;
}
