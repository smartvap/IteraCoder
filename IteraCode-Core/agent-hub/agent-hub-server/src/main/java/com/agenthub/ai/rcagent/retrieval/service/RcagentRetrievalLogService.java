package com.agenthub.ai.rcagent.retrieval.service;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.retrieval.common.RetrievalDateUtils;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalLogPageQuery;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalStatsQuery;
import com.agenthub.ai.rcagent.retrieval.entity.RcagentRetrievalLog;
import com.agenthub.ai.rcagent.retrieval.mapper.RcagentRetrievalLogMapper;
import com.agenthub.ai.rcagent.retrieval.vo.RcagentRetrievalLogVO;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalDailyStatVO;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalStatsOverviewVO;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalStatsVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 检索质量日志读写服务（FR-BE-007 / FR-007）。
 *
 * <p>写日志失败不影响检索结果（try/catch 兜底，NFR-BE-001）；
 * question/evidence 等长文本在 VO 输出一律截断（AGENTS.md 5.3/5.4）。</p>
 */
@Slf4j
@Service
public class RcagentRetrievalLogService {

    private static final int QUESTION_SHOW_MAX = 200;

    private final RcagentRetrievalLogMapper logMapper;

    public RcagentRetrievalLogService(RcagentRetrievalLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    /**
     * 检索质量观测日志落库（try/catch 兜底不阻塞主检索）。
     *
     * @param record 已组装的观测记录（内部由 RetrievalService 组装）
     */
    public void record(RcagentRetrievalLog record) {
        if (record == null) {
            return;
        }
        try {
            if (record.getCreateTime() == null) {
                record.setCreateTime(new Date());
            }
            if (record.getQuestion() != null && record.getQuestion().length() > QUESTION_SHOW_MAX) {
                record.setQuestion(record.getQuestion().substring(0, QUESTION_SHOW_MAX));
            }
            logMapper.insert(record);
        } catch (Exception e) {
            log.warn("retrieval: 检索观测日志落库失败(不阻塞检索): {}", e.getMessage());
        }
    }

    /** 质量日志分页（接口2）：records 内 question 截断展示 */
    public PageResult pageLogs(RetrievalLogPageQuery query) {
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<RcagentRetrievalLog> page = logMapper.selectPageLogs(
                new Page<>(query.page(), query.pageSize()),
                blankToNull(query.getDsId()),
                blankToNull(query.getThreadId()),
                query.getColdStart(),
                RetrievalDateUtils.parse(query.getStartTime()),
                RetrievalDateUtils.parse(query.getEndTime()));
        List<RcagentRetrievalLogVO> records = new ArrayList<>(page.getRecords().size());
        for (RcagentRetrievalLog entity : page.getRecords()) {
            records.add(toVO(entity));
        }
        return new PageResult(page.getTotal(), records);
    }

    /** 质量指标聚合（接口3，FR-007 P2） */
    public RetrievalStatsVO stats(RetrievalStatsQuery query) {
        if (query == null) {
            throw new BusinessException(42102, "请求参数不能为空");
        }
        if (query.getStartTime() == null || query.getStartTime().isBlank()
                || query.getEndTime() == null || query.getEndTime().isBlank()) {
            throw new BusinessException(42102, "start_time/end_time 必填");
        }
        if (query.getGranularity() != null && !query.getGranularity().isBlank()
                && !"day".equalsIgnoreCase(query.getGranularity())
                && !"week".equalsIgnoreCase(query.getGranularity())) {
            throw new BusinessException(42102, "granularity 非法");
        }
        Date start = RetrievalDateUtils.parse(query.getStartTime());
        Date end = RetrievalDateUtils.parse(query.getEndTime());
        if (start == null || end == null) {
            throw new BusinessException(42102, "start_time/end_time 格式非法");
        }
        String dsId = blankToNull(query.getDsId());
        String granularity = "week".equalsIgnoreCase(query.getGranularity()) ? "week" : "day";

        RetrievalStatsOverviewVO overview = logMapper.selectStatsOverview(dsId, start, end);
        List<RetrievalDailyStatVO> daily = logMapper.selectStatsDaily(dsId, start, end, granularity);
        if (overview == null) {
            overview = new RetrievalStatsOverviewVO();
        }
        if (daily == null) {
            daily = new ArrayList<>();
        }
        RetrievalStatsVO vo = new RetrievalStatsVO();
        vo.setOverview(overview);
        vo.setDaily(daily);
        return vo;
    }

    private RcagentRetrievalLogVO toVO(RcagentRetrievalLog entity) {
        RcagentRetrievalLogVO vo = new RcagentRetrievalLogVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setQuestion(truncate(entity.getQuestion(), QUESTION_SHOW_MAX));
        vo.setQueryElementsJson(truncate(entity.getQueryElementsJson(), QUESTION_SHOW_MAX));
        vo.setChannelHitJson(truncate(entity.getChannelHitJson(), 500));
        if (entity.getCreateTime() != null) {
            vo.setCreateTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(entity.getCreateTime()));
        }
        return vo;
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
