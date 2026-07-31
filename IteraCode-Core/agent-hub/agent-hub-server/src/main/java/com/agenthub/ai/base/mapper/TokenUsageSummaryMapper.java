package com.agenthub.ai.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agenthub.ai.base.entity.TokenUsageSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description 针对表【token_usage_summary】的数据库操作Mapper
 * @Entity com.agenthub.ai.base.entity.TokenUsageSummary
 */
@Mapper
public interface TokenUsageSummaryMapper extends BaseMapper<TokenUsageSummary> {
}
