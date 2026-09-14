package com.agenthub.ai.base.mapper;

import com.agenthub.ai.base.entity.SensitiveWord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词 Mapper
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {
}
