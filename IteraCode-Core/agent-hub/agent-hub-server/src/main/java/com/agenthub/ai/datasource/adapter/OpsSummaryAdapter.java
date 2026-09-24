package com.agenthub.ai.datasource.adapter;

import com.agenthub.ai.datasource.vo.OpsSummaryVO;
import org.springframework.stereotype.Component;

/**
 * 运维概况只读聚合适配器（FR-BE-007，P2 可选）。
 *
 * <p>module-003 kb-schema-crawler / module-004 kb-storage-review / module-008 feedback-loop
 * 尚未向本模块暴露只读聚合 SPI，本适配器返回空对象 + degradation 提示（禁止跨模块直接查表/臆造方法）。</p>
 */
@Component
public class OpsSummaryAdapter {

    /**
     * 数据源视角运维概况；依赖模块未接入时返回空对象 + degradation，不抛错。
     */
    public OpsSummaryVO summary(String dsId) {
        OpsSummaryVO vo = new OpsSummaryVO();
        vo.setDsId(dsId);
        vo.setLastCrawl(null);
        vo.setPendingReviewCount(0);
        vo.setFeedbackCount(0);
        vo.setDegradation("依赖模块未接入：module-003(kb-schema-crawler)/module-004(kb-storage-review)/module-008(feedback-loop)");
        return vo;
    }
}
