package com.agenthub.ai.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agenthub.ai.base.entity.TokenUsageDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description 针对表【token_usage_detail】的数据库操作Mapper
 * @Entity com.agenthub.ai.base.entity.TokenUsageDetail
 */
@Mapper
public interface TokenUsageDetailMapper extends BaseMapper<TokenUsageDetail> {
}
