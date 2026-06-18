package com.agenthub.ai.base.pojo.dto;

import lombok.Data;

/**
 * @Title: QueryFileDTO
 * 
 * @Package com.agenthub.ai.pojo.dto
 * @Date
 * @description: 查找文件dto
 */
@Data
public class QueryFileDTO {
    private Integer page;
    private Integer pageSize;
    private String fileName;
}
