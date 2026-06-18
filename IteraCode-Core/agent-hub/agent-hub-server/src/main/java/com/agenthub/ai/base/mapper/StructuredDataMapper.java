package com.agenthub.ai.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agenthub.ai.base.entity.StructuredData;
import org.apache.ibatis.annotations.Mapper;

/**
 * 结构化数据 Mapper 接口
 */
@Mapper
public interface StructuredDataMapper extends BaseMapper<StructuredData> {
}