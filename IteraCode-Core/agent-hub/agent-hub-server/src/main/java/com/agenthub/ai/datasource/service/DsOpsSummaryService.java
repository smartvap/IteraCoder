package com.agenthub.ai.datasource.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.datasource.adapter.OpsSummaryAdapter;
import com.agenthub.ai.datasource.constant.DsErrorCode;
import com.agenthub.ai.datasource.entity.DsConfig;
import com.agenthub.ai.datasource.mapper.DsConfigManageMapper;
import com.agenthub.ai.datasource.vo.OpsSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 数据源运维概况服务（FR-BE-007，P2 可选）：依赖模块未接入时返回空对象 + degradation，不抛错。
 */
@Service
public class DsOpsSummaryService {

    private final DsConfigManageMapper configManageMapper;
    private final OpsSummaryAdapter opsSummaryAdapter;

    public DsOpsSummaryService(DsConfigManageMapper configManageMapper,
                               OpsSummaryAdapter opsSummaryAdapter) {
        this.configManageMapper = configManageMapper;
        this.opsSummaryAdapter = opsSummaryAdapter;
    }

    public OpsSummaryVO opsSummary(String dsId) {
        DsConfig config = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .eq(DsConfig::getIsDeleted, 0));
        if (config == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在或已删除");
        }
        return opsSummaryAdapter.summary(dsId);
    }
}
