package com.agenthub.ai.datasource.dto;

import lombok.Data;

import java.util.List;

/**
 * 空间绑定/重试入参（api-contract SpaceBindRequest）。
 */
@Data
public class SpaceBindRequest {

    /** 目标 llm-wiki 知识空间名（必填） */
    private String spaceName;

    /** 空间不存在时是否自动创建（默认 true） */
    private Boolean autoCreate;

    /** 空间类型（传给 llm-wiki createSpace；空由远端默认） */
    private List<String> spaceTypes;
}
