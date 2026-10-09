package com.agenthub.ai.rcagent.engine.mapper;

import com.agenthub.ai.rcagent.engine.entity.RcaReviewAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * RCAgent 审核审计 Mapper（追加型记录，无软删除）。
 */
@Mapper
public interface RcaReviewAuditMapper extends BaseMapper<RcaReviewAudit> {
}
