package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.adapter.KbFeedbackAdapter;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackCountItem;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackPageRequest;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackStatsRequest;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.agenthub.ai.rcagent.feedback.entity.WikiWritebackTask;
import com.agenthub.ai.rcagent.feedback.mapper.FeedbackFlowMapper;
import com.agenthub.ai.rcagent.feedback.mapper.WikiWritebackTaskMapper;
import com.agenthub.ai.rcagent.feedback.model.KnowledgeSuggestion;
import com.agenthub.ai.rcagent.feedback.util.FeedbackDateUtil;
import com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackFlowVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackRowVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackStatsVO;
import com.agenthub.ai.rcagent.feedback.vo.SuggestionVO;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈闭环查询（接口2 详情 / 接口3 列表 / 接口4 统计）。
 *
 * <p>详情解析 fb_feedback_flow JSON 列（diff_summary/suggestions/review_task_ids）；
 * 列表走 MP PaginationInnerInterceptor 分页；统计基于 feedback_type/status GROUP BY + fb_wiki_writeback 状态分布
 * （MVP 可量化部分，aObjects=完成库 A 写回流数，bPages/wikiSuccess/wikiFailed=库 B 出站统计）。</p>
 */
@Slf4j
@Service
public class FeedbackQueryService {

    private final FeedbackFlowMapper flowMapper;
    private final WikiWritebackTaskMapper wikiTaskMapper;
    private final KbFeedbackAdapter kbFeedbackAdapter;
    private final WikiWritebackQueryHelper wikiQueryHelper;

    public FeedbackQueryService(FeedbackFlowMapper flowMapper,
                                WikiWritebackTaskMapper wikiTaskMapper,
                                KbFeedbackAdapter kbFeedbackAdapter,
                                WikiWritebackQueryHelper wikiQueryHelper) {
        this.flowMapper = flowMapper;
        this.wikiTaskMapper = wikiTaskMapper;
        this.kbFeedbackAdapter = kbFeedbackAdapter;
        this.wikiQueryHelper = wikiQueryHelper;
    }

    /** 接口2 详情 */
    public FeedbackFlowVO detail(Long flowId) {
        FeedbackFlow flow = flowMapper.selectById(flowId);
        if (flow == null) {
            throw new BusinessException(FeedbackErrorCode.FLOW_NOT_EXIST, "反馈处理流不存在: flowId=" + flowId);
        }
        FeedbackFlowVO vo = new FeedbackFlowVO();
        vo.setFlowId(flow.getId());
        vo.setFeedbackId(flow.getFeedbackId());
        vo.setDsId(flow.getDsId());
        vo.setSessionId(flow.getSessionId());
        vo.setFeedbackType(flow.getFeedbackType());
        vo.setChangeType(flow.getChangeType());
        vo.setChangeConfidence(flow.getChangeConfidence());
        vo.setAnalyzeStatus(flow.getAnalyzeStatus());
        vo.setStatus(flow.getStatus());
        vo.setWritebackRepo(flow.getWritebackRepo());
        vo.setErrorMsg(flow.getErrorMsg());
        vo.setCreateTime(flow.getCreateTime());
        vo.setUpdateTime(flow.getUpdateTime());
        vo.setDiffSummary(FeedbackJsonUtil.parseObject(flow.getDiffSummary()));

        List<KnowledgeSuggestion> suggestions =
                FeedbackJsonUtil.parseList(flow.getSuggestions(), KnowledgeSuggestion.class);
        List<SuggestionVO> suggestionVos = new ArrayList<>();
        for (KnowledgeSuggestion suggestion : suggestions) {
            suggestionVos.add(suggestion.toVO());
        }
        vo.setSuggestions(suggestionVos.isEmpty() ? null : suggestionVos);
        vo.setReviewTaskIds(FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds()));
        vo.setWikiWritebackStatus(wikiQueryHelper.latestStatus(flow.getDsId(), flow.getFeedbackId()));
        return vo;
    }

    /** 接口3 列表 */
    public PageResult page(FeedbackPageRequest request) {
        if (request == null || request.getDsId() == null || request.getDsId().isBlank()) {
            // 参数校验失败属请求参数错误（40000），而非流不存在（45001）
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空");
        }
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<FeedbackFlow> page = flowMapper.selectPageFlows(
                new Page<>(request.page(), request.pageSize()),
                request.getDsId(),
                request.statusList(),
                request.getFeedbackType(),
                request.getChangeType(),
                request.getSessionId(),
                FeedbackDateUtil.parse(request.getStartTime()),
                FeedbackDateUtil.parse(request.getEndTime()));

        // 批量回溯 question（module-004 kb_feedback 最近 200 条内存匹配，MVP）
        Map<Long, String> questionMap = loadQuestionMap(request.getDsId());

        List<FeedbackRowVO> records = new ArrayList<>(page.getRecords().size());
        for (FeedbackFlow flow : page.getRecords()) {
            FeedbackRowVO vo = new FeedbackRowVO();
            vo.setFlowId(flow.getId());
            vo.setFeedbackId(flow.getFeedbackId());
            vo.setDsId(flow.getDsId());
            vo.setSessionId(flow.getSessionId());
            vo.setQuestion(questionMap.get(flow.getFeedbackId()));
            vo.setFeedbackType(flow.getFeedbackType());
            vo.setChangeType(flow.getChangeType());
            vo.setStatus(flow.getStatus());
            vo.setAnalyzeStatus(flow.getAnalyzeStatus());
            vo.setReviewTaskCount(FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds()).size());
            vo.setWritebackRepo(flow.getWritebackRepo());
            vo.setCreateTime(flow.getCreateTime());
            vo.setUpdateTime(flow.getUpdateTime());
            records.add(vo);
        }
        return new PageResult(page.getTotal(), records);
    }

    private Map<Long, String> loadQuestionMap(String dsId) {
        Map<Long, String> map = new HashMap<>();
        try {
            com.agenthub.ai.rcagent.kb.dto.KbFeedbackPageDTO query = new com.agenthub.ai.rcagent.kb.dto.KbFeedbackPageDTO();
            query.setDsId(dsId);
            query.setPage(1);
            query.setPageSize(200);
            PageResult result = kbFeedbackAdapter.page(query);
            if (result != null && result.getRecords() != null) {
                for (Object record : result.getRecords()) {
                    if (record instanceof KbFeedbackVO kb) {
                        map.put(kb.getId(), kb.getQuestion());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("feedback: 问题摘要批量回溯失败 dsId={}: {}", dsId, e.getMessage());
        }
        return map;
    }

    /** 接口4 统计 */
    public FeedbackStatsVO stats(FeedbackStatsRequest request) {
        if (request == null || request.getDsId() == null || request.getDsId().isBlank()) {
            // 参数校验失败属请求参数错误（40000），而非流不存在（45001）
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空");
        }
        Date start = FeedbackDateUtil.parse(request.getStartTime());
        Date end = FeedbackDateUtil.parse(request.getEndTime());

        List<FeedbackCountItem> typeItems = flowMapper.countByFeedbackType(request.getDsId(), start, end);
        List<FeedbackCountItem> statusItems = flowMapper.countByStatus(request.getDsId(), start, end);
        List<FeedbackCountItem> wikiItems = wikiTaskMapper.countWikiByStatus(request.getDsId());

        Map<String, Long> typeDist = toMap(typeItems);
        Map<String, Long> statusDist = toMap(statusItems);
        long total = typeDist.values().stream().mapToLong(Long::longValue).sum();
        long closed = statusDist.getOrDefault("CLOSED", 0L);
        long pendingReview = statusDist.getOrDefault("REVIEWING", 0L);

        Long aObjects = flowMapper.selectCount(new QueryWrapper<FeedbackFlow>()
                .eq("ds_id", request.getDsId())
                .eq("a_writeback_done", 1));
        Map<String, Long> wikiDist = toMap(wikiItems);

        FeedbackStatsVO vo = new FeedbackStatsVO();
        vo.setTotal(total);
        vo.setTypeDistribution(typeDist.isEmpty() ? null : typeDist);
        vo.setStatusDistribution(statusDist.isEmpty() ? null : statusDist);
        vo.setPendingReview(pendingReview);
        vo.setClosedRate(total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(closed).divide(BigDecimal.valueOf(total), 4, BigDecimal.ROUND_HALF_UP));

        FeedbackStatsVO.WritebackStatVO writeback = new FeedbackStatsVO.WritebackStatVO();
        writeback.setAObjects(aObjects);
        writeback.setBPages(wikiDist.values().stream().mapToLong(Long::longValue).sum());
        writeback.setWikiSuccess(wikiDist.getOrDefault("SUCCESS", 0L));
        writeback.setWikiFailed(wikiDist.getOrDefault("FAILED", 0L));
        vo.setWritebackCount(writeback);
        return vo;
    }

    private Map<String, Long> toMap(List<FeedbackCountItem> items) {
        Map<String, Long> map = new HashMap<>();
        if (items == null) {
            return map;
        }
        for (FeedbackCountItem item : items) {
            map.put(item.getGroupKey(), item.getCnt());
        }
        return map;
    }
}

