package com.agenthub.ai.base.pojo.dto;

import lombok.Data;

import java.util.List;

/**
 * RAG查询请求DTO
 */
@Data
public class RagQueryRequest {
    private List<String> sources;
    private String message;
}