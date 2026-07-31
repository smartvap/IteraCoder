package com.agenthub.ai.base.pojo.dto;

import lombok.Data;

@Data
public class TokenUsageQueryDTO {
    private String startDate;
    private String endDate;
    private int page = 1;
    private int pageSize = 20;
}
