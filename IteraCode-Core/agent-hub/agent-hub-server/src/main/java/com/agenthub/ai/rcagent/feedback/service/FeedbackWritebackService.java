package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.adapter.ConfidenceAdapter;
import com.agenthub.ai.rcagent.feedback.adapter.DsConfigResolver;
import com.agenthub.ai.rcagent.feedback.adapter.KnowledgeSaveAdapter;
import com.agenthub.ai.rcagent.feedback.adapter.LlmWikiWritebackAdapter;
import com.agenthub.ai.rcagent.feedback.adapter.ReviewTaskAdapter;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackAnalyzeStatus;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackStatus;
import com.agenthub.ai.rcagent.feedback.constant.WikiWritebackStatus;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.agenthub.ai.rcagent.feedback.entity.WikiWritebackTask;
import com.agenthub.ai.rcagent.feedback.mapper.FeedbackFlowMapper;
import com.agenthub.ai.rcagent.feedback.mapper.WikiWritebackTaskMapper;
import com.agenthub.ai.rcagent.feedback.model.KnowledgeSuggestion;
import com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackWritebackVO;
import com.agenthub.ai.rcagent.kb.vo.KbSaveResultVO;
import com.agenthub.ai.llmwiki.dto.PageVO;
import com.agenthub.ai.llmwiki.dto.SyncTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 触发回写（接口7 writeback：A/B 库回写 + 置信度推进）。
 *
 * <p>前置：flow.status ∈ APPROVED/WRITTEN_BACK/FAILED；REVIEWING 仅在全部审核任务
 * approved 时自动推进 APPROVED 后回写。force=true 仅保留「终态/失败重试」的宽容语义
 * （供调试复写 FAILED 等），<b>不放开审批闸门</b>——任何状态（含 RECEIVED/ANALYZING/
 * REVIEWING 未批准）都不得绕过人工审核直接写知识（BR-001）。
 * 流程：
 * <ol>
 *   <li>遍历 flow.suggestions：库 A 经 KnowledgeSaveAdapter knowledge save（evidence 含
 *       feedback id + review task id，BR-003）；</li>
 *   <li>涉及库 B（FAQ/概念/指标口径）→ 建 fb_wiki_writeback 出站并调存量 LlmWikiClient
 *       写页 + syncPages，远端不可用置 PENDING/FAILED 重试，<b>不阻塞库 A 与闭环</b>；</li>
 *   <li>置信度 confirm/positive/negative 推进（signal_key 幂等，记 flow.signal_keys）；</li>
 *   <li>更新 flow：a_writeback_done/b_writeback_done/writeback_repo/status=CLOSED，
 *       失败置 FAILED 可重试（retry_count++）。</li>
 * </ol></p>
 */
@Slf4j
@Service
public class FeedbackWritebackService {

    private static final int WIKI_RETRY_MAX = 5;
    private static final String DEFAULT_REVIEWER = "feedback-writeback";

    private final FeedbackFlowMapper flowMapper;
    private final WikiWritebackTaskMapper wikiTaskMapper;
    private final KnowledgeSaveAdapter knowledgeSaveAdapter;
    private final LlmWikiWritebackAdapter wikiWritebackAdapter;
    private final ConfidenceAdapter confidenceAdapter;
    private final DsConfigResolver dsConfigResolver;
    private final ReviewTaskAdapter reviewTaskAdapter;

    public FeedbackWritebackService(FeedbackFlowMapper flowMapper,
                                    WikiWritebackTaskMapper wikiTaskMapper,
                                    KnowledgeSaveAdapter knowledgeSaveAdapter,
                                    LlmWikiWritebackAdapter wikiWritebackAdapter,
                                    ConfidenceAdapter confidenceAdapter,
                                    DsConfigResolver dsConfigResolver,
                                    ReviewTaskAdapter reviewTaskAdapter) {
        this.flowMapper = flowMapper;
        this.wikiTaskMapper = wikiTaskMapper;
        this.knowledgeSaveAdapter = knowledgeSaveAdapter;
        this.wikiWritebackAdapter = wikiWritebackAdapter;
        this.confidenceAdapter = confidenceAdapter;
        this.dsConfigResolver = dsConfigResolver;
        this.reviewTaskAdapter = reviewTaskAdapter;
    }

    /**
     * 触发回写。
     *
     * @param force 是否强制重试（仅终态/失败重试的宽容语义；不放开审批闸门，BR-001）
     */
    public FeedbackWritebackVO writeback(Long flowId, boolean force) {
        FeedbackFlow flow = flowMapper.selectById(flowId);
        if (flow == null) {
            throw new BusinessException(FeedbackErrorCode.FLOW_NOT_EXIST, "反馈处理流不存在: flowId=" + flowId);
        }
        String status = flow.getStatus() == null ? "" : flow.getStatus();
        // CLOSED 幂等成功
        if ("CLOSED".equalsIgnoreCase(status)) {
            FeedbackWritebackVO vo = new FeedbackWritebackVO();
            vo.setFlowId(flowId);
            vo.setStatus("CLOSED");
            vo.setAWriteback(0L);
            vo.setBWriteback(0L);
            vo.setRepo(flow.getWritebackRepo());
            vo.setFailedReasons(new ArrayList<>());
            return vo;
        }
        boolean reviewing = "REVIEWING".equalsIgnoreCase(status);
        if (reviewing) {
            // 审批闸门与状态校验解耦：REVIEWING 无论 force 与否，仅当全部审核任务 approved
            // 才自动推进 APPROVED 并回写；未全量批准一律 45004（force 不得绕过 BR-001）
            if (!allReviewsApproved(flow)) {
                throw new BusinessException(FeedbackErrorCode.STATUS_NOT_ALLOWED,
                        "当前状态不允许回写: 审核任务未全部批准（BR-001 禁止未批准建议写入知识）");
            }
            flow.setStatus(FeedbackStatus.APPROVED.getValue());
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
            status = FeedbackStatus.APPROVED.getValue();
        }
        // 状态校验：APPROVED/WRITTEN_BACK/FAILED（FAILED 表示上次回写失败允许重试；
        // force 仅对这类已批准/终态流放宽，不新增放行路径）
        if (!("APPROVED".equalsIgnoreCase(status)
                || "WRITTEN_BACK".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status))) {
            throw new BusinessException(FeedbackErrorCode.STATUS_NOT_ALLOWED,
                    "当前状态不允许回写: status=" + status + "（需 APPROVED/审核通过后触发）");
        }
        // FAILED 放行前须满足「至少一条建议已批准」契约前置（FR-BE-005 / 45004）：
        // 分析失败流（analyze_status != REVIEW_CREATED、无 approved 审核任务）不得被 writeback
        // 洗成 CLOSED 从而永久丢失重试机会——此类流应重新 analyze/classify 或走人工重跑
        if ("FAILED".equalsIgnoreCase(status) && !hasApprovedReview(flow)) {
            throw new BusinessException(FeedbackErrorCode.STATUS_NOT_ALLOWED,
                    "当前流无已批准审核任务，请先 analyze/classify 或走人工重跑后再回写（45004）");
        }

        flow.setStatus("WRITTEN_BACK");
        flow.setErrorMsg(null);
        flow.setUpdateTime(new Date());
        flowMapper.updateById(flow);

        List<KnowledgeSuggestion> suggestions =
                FeedbackJsonUtil.parseList(flow.getSuggestions(), KnowledgeSuggestion.class);
        List<String> failedReasons = new ArrayList<>();
        long aOk = 0;
        long bOk = 0;
        List<String> repoDone = new ArrayList<>();
        List<String> newSignalKeys = new ArrayList<>(parseSignalKeys(flow));
        // 依赖调用失败（45005）标记：出现即强制 FAILED 可重试，不允许当作闭环成功
        boolean depError = false;

        // 回写执行上下文（reviewer：登录态占位 userId）
        String reviewer = BaseContext.getCurrentId() == null ? DEFAULT_REVIEWER
                : String.valueOf(BaseContext.getCurrentId());

        for (int i = 0; i < suggestions.size(); i++) {
            KnowledgeSuggestion suggestion = suggestions.get(i);
            if (suggestion == null || !suggestion.actionable()) {
                failedReasons.add("suggestion[" + i + "]: 目标对象未收录/不可回写（应转新知识采集，45007）");
                continue;
            }
            String evidence = "feedback#" + flow.getId() + ";review#"
                    + reviewTaskIdAt(flow, i);

            // ---- 库 A 回写 ----
            try {
                KbSaveResultVO saved = knowledgeSaveAdapter.applyAWriteback(
                        flow.getDsId(), suggestion, evidence);
                if (saved != null && saved.getObjectId() != null) {
                    aOk++;
                    if (!repoDone.contains("A")) {
                        repoDone.add("A");
                    }
                    // 置信度：批准目标 confirm → 1.0/error=0/active（signal_key 幂等）
                    String signalKey = "feedback#" + flow.getId() + ":"
                            + suggestion.getTargetObjectType() + ":" + saved.getObjectId() + ":confirm";
                    if (!newSignalKeys.contains(signalKey)) {
                        confidenceAdapter.confirm(flow.getDsId(), suggestion.getTargetObjectType(),
                                saved.getObjectId(), reviewer, evidence);
                        newSignalKeys.add(signalKey);
                    }
                } else if (saved == null) {
                    // 目标缺失（patch 缺必要字段/未收录）——不阻塞其它建议
                    failedReasons.add("suggestion[" + i + "]: 库 A 回写目标缺失（45007 语义，转人工采集）");
                }
            } catch (Exception e) {
                log.warn("feedback: 库 A 回写失败 flowId={} sug[{}]: {}", flowId, i, e.getMessage());
                // 区分失败类型：45005 依赖调用失败（module-004/llm-wiki 不可用）→ depError=true
                // 强制 FAILED 可重试，避免依赖故障被当作闭环成功；其余失败仅记 failedReasons
                boolean depCallFailed = e instanceof BusinessException
                        && ((BusinessException) e).getCode() == FeedbackErrorCode.DEPENDENCY_CALL_FAILED;
                if (depCallFailed) {
                    depError = true;
                }
                failedReasons.add("suggestion[" + i + "]: 库 A 回写失败"
                        + (depCallFailed ? "（依赖调用失败 45005，可重试）" : "") + ": "
                        + FeedbackSafeText.truncate(e.getMessage(), 120));
            }

            // ---- 库 B 回写（repo 含 B，FAQ/概念/指标口径）----
            if (suggestion.getRepo() != null && suggestion.getRepo().contains("B")) {
                boolean okB = writeWikiBack(flow, suggestion, i, reviewer, failedReasons);
                if (okB) {
                    bOk++;
                    if (!repoDone.contains("B")) {
                        repoDone.add("B");
                    }
                }
            }
        }

        // 置信度信号持久化
        if (!newSignalKeys.isEmpty()) {
            flow.setSignalKeys(FeedbackJsonUtil.toJson(newSignalKeys));
            flow.setConfidencePushed(1);
        }

        // 完成标记：repoDone 为空表示本轮没有实际可写目标（如全部 NEW 采集建议）
        boolean aDone = repoDone.contains("A");
        boolean bDone = repoDone.contains("B");
        flow.setAWritebackDone(aDone ? 1 : 0);
        flow.setBWritebackDone(bDone ? 1 : 0);

        // 致命失败判定（区分失败类型，避免依赖故障 / 目标缺失被当作闭环成功）：
        //  1) 依赖调用失败（45005）→ 一律 FAILED 可重试，不允许 CLOSED（依赖不可用非成功闭环）；
        //  2) 存在 A 类型建议但库 A 全部失败（aOk=0）→ FAILED 可重试；
        //  3) 本轮无任何成功写入（repoDone 空且建议非空，如全部 45007 目标缺失/新知识采集）
        //     → 不静默 CLOSED，落 FAILED 提示转人工补全，确保错误场景对运营可见
        boolean hasATarget = suggestions.stream().anyMatch(s ->
                s != null && s.getRepo() != null && s.getRepo().contains("A"));
        boolean allBlockedNoWrite = aOk == 0 && repoDone.isEmpty() && !suggestions.isEmpty();
        boolean fatalA = depError || (hasATarget && aOk == 0) || allBlockedNoWrite;
        if (fatalA) {
            flow.setStatus("FAILED");
            String headline = depError
                    ? "依赖调用失败（45005）致回写未全部完成，请恢复依赖后重试"
                    : allBlockedNoWrite
                            ? "全部建议不可回写，需转新知识采集或人工补全目标对象（45007）"
                            : null;
            String detail = failedReasons.isEmpty() ? headline
                    : headline == null ? String.join("; ", failedReasons)
                    : headline + " | " + String.join("; ", failedReasons);
            flow.setErrorMsg(FeedbackSafeText.truncate(detail == null ? "" : detail, 1024));
            flow.setRetryCount((flow.getRetryCount() == null ? 0 : flow.getRetryCount()) + 1);
        } else {
            flow.setStatus("CLOSED");
            flow.setWritebackRepo(FeedbackJsonUtil.joinRepo(repoDone));
        }
        flow.setUpdateTime(new Date());
        flowMapper.updateById(flow);

        FeedbackWritebackVO vo = new FeedbackWritebackVO();
        vo.setFlowId(flowId);
        vo.setStatus(flow.getStatus());
        vo.setAWriteback(aOk);
        vo.setBWriteback(bOk);
        vo.setRepo(FeedbackJsonUtil.joinRepo(repoDone));
        vo.setFailedReasons(failedReasons.isEmpty() ? null : failedReasons);
        return vo;
    }

    /**
     * 库 B 单条回写：建/取 fb_wiki_writeback 行并同步执行写页 + syncPages。
     * 远端失败不抛，置 FAILED/PENDING 供调度重试（不阻塞库 A 与闭环）。
     *
     * @return 是否本次执行成功（远端可用且页面写入成功）
     */
    public boolean writeWikiBack(FeedbackFlow flow, KnowledgeSuggestion suggestion,
                                 int suggestionNo, String reviewer, List<String> failedReasons) {
        Long reviewTaskId = reviewTaskIdAt(flow, suggestionNo);
        String space = dsConfigResolver.resolveSpaceName(flow.getDsId());
        if (space == null || space.isBlank()) {
            recordWikiFailure(flow, reviewTaskId, suggestionNo, "数据源未配置 llm-wiki space_name");
            failedReasons.add("suggestion[" + suggestionNo + "]: 库 B 空间未配置，跳过（可人工补配后重试）");
            return false;
        }
        String pageTitle = suggestion.getTargetDesc();
        if (pageTitle == null || pageTitle.isBlank()) {
            pageTitle = suggestion.getTargetObjectType().toLowerCase();
        }
        String pagePath = pagePathOf(suggestion, pageTitle);
        String pageContent = buildWikiContent(suggestion);
        String pageType = wikiPageType(suggestion);

        // uk_ds_page 幂等：同 (ds_id, review_task_id, page_path) 只保留一条出站任务
        WikiWritebackTask existing = wikiTaskMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WikiWritebackTask>()
                .eq("ds_id", flow.getDsId())
                .eq("review_task_id", reviewTaskId == null ? 0L : reviewTaskId)
                .eq("page_path", pagePath)
                .last("LIMIT 1"));
        if (existing != null) {
            // 已存在任务：RUNNING/SUCCESS 视为已处理；FAILED/PENDING 由调度器重试扫描执行
            return !(WikiWritebackStatus.PENDING.getValue().equals(existing.getStatus())
                    || WikiWritebackStatus.FAILED.getValue().equals(existing.getStatus()));
        }

        WikiWritebackTask task = new WikiWritebackTask();
        task.setDsId(flow.getDsId());
        task.setFeedbackId(flow.getFeedbackId());
        task.setReviewTaskId(reviewTaskId == null ? 0L : reviewTaskId);
        task.setSuggestionNo(suggestionNo);
        task.setWikiSpace(space);
        task.setPagePath(pagePath);
        task.setPageTitle(pageTitle);
        task.setPageContent(pageContent);
        task.setPageType(pageType);
        task.setStatus(WikiWritebackStatus.PENDING.getValue());
        task.setRetryCount(0);
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        wikiTaskMapper.insert(task);

        // 执行一次出站写页（PENDING → RUNNING → SUCCESS / FAILED）
        try {
            wikiTaskMapper.markRunning(task.getId(), new Date());
            PageVO page = wikiWritebackAdapter.writePage(space, pagePath, pageContent);
            // 写页成功后发起异步索引同步（远端任务轮询交给调度器）
            try {
                SyncTaskVO sync = wikiWritebackAdapter.syncPages(space);
                wikiTaskMapper.markRemoteTask(task.getId(), sync == null ? null : sync.taskId(), new Date());
                if (LlmWikiWritebackAdapter.isTaskCompleted(sync)) {
                    wikiTaskMapper.markSuccess(task.getId(), new Date());
                }
            } catch (Exception e) {
                // sync 失败不阻塞页面写入：保留 RUNNING/SUCCESS 记录，由调度器补同步
                log.warn("feedback: llm-wiki sync 发起失败 space={} path={}: {}",
                        space, pagePath, e.getMessage());
                wikiTaskMapper.markSuccess(task.getId(), new Date());
            }
            return true;
        } catch (Exception e) {
            log.warn("feedback: llm-wiki 写页失败 space={} path={}: {}", space, pagePath, e.getMessage());
            // 远端不可用：置 FAILED 并设置指数退避下次重试（不阻塞库 A）
            int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            Date nextRetry = retry >= WIKI_RETRY_MAX ? null
                    : new Date(System.currentTimeMillis() + backoffMillis(retry));
            wikiTaskMapper.markFailed(task.getId(),
                    FeedbackSafeText.truncate(e.getMessage(), 200), nextRetry, new Date());
            failedReasons.add("suggestion[" + suggestionNo + "]: 库 B 写页失败(可重试): "
                    + FeedbackSafeText.truncate(e.getMessage(), 120));
            return false;
        }
    }

    /** 幂等插入/复用 B 库任务失败记录（无远端写能力时的占位，供人工定位） */
    private void recordWikiFailure(FeedbackFlow flow, Long reviewTaskId, int suggestionNo, String msg) {
        WikiWritebackTask task = new WikiWritebackTask();
        task.setDsId(flow.getDsId());
        task.setFeedbackId(flow.getFeedbackId());
        task.setReviewTaskId(reviewTaskId == null ? 0L : reviewTaskId);
        task.setSuggestionNo(suggestionNo);
        task.setPageTitle("(unresolved)");
        task.setPagePath("(unresolved)");
        task.setStatus(WikiWritebackStatus.FAILED.getValue());
        task.setLastError(FeedbackSafeText.truncate(msg, 200));
        task.setRetryCount(WIKI_RETRY_MAX);
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        wikiTaskMapper.insert(task);
    }

    private String pagePathOf(KnowledgeSuggestion suggestion, String pageTitle) {
        // 概念页放 concepts/ 子目录；指标口径页放 metrics/；其余按类型前缀
        String dir = switch (suggestion.getTargetObjectType()) {
            case "BUSINESS_CONCEPT" -> "concepts";
            case "METRIC" -> "metrics";
            default -> "feedback";
        };
        return dir + "/" + safeFileName(pageTitle) + ".md";
    }

    private String buildWikiContent(KnowledgeSuggestion suggestion) {
        com.alibaba.fastjson2.JSONObject patch = suggestion.patchObject();
        com.alibaba.fastjson2.JSONObject bContent = patch.getJSONObject("b_content");
        if (bContent != null && bContent.getString("page_content") != null) {
            return bContent.getString("page_content");
        }
        StringBuilder sb = new StringBuilder("# ").append(suggestion.getTargetDesc() == null
                ? "知识沉淀页" : suggestion.getTargetDesc()).append("\n\n");
        String definition = patch.getString("definition");
        if (definition != null && !definition.isBlank()) {
            sb.append(definition);
        } else {
            sb.append("由用户反馈沉淀并经人工审核的知识补充（evidence=")
                    .append(FeedbackSafeText.truncate(suggestion.getEvidence(), 60)).append("）。");
        }
        String text = sb.toString();
        return text.length() > 5000 ? text.substring(0, 5000) : text;
    }

    private String wikiPageType(KnowledgeSuggestion suggestion) {
        return switch (suggestion.getTargetObjectType()) {
            case "METRIC" -> "METRIC_CALIBER";
            case "BUSINESS_CONCEPT" -> "CONCEPT";
            default -> "FAQ";
        };
    }

    private String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "page";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private long backoffMillis(int retry) {
        // 指数退避：30s / 1m / 2m / 4m / 8m
        long base = 30_000L;
        return base * (1L << Math.min(retry, 5));
    }

    private List<String> parseSignalKeys(FeedbackFlow flow) {
        return new ArrayList<>(FeedbackJsonUtil.parseList(
                flow.getSignalKeys() == null ? "[]" : flow.getSignalKeys(), String.class));
    }

    private Long reviewTaskIdAt(FeedbackFlow flow, int index) {
        List<Long> ids = FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds());
        if (ids.isEmpty() || index >= ids.size()) {
            return null;
        }
        return ids.get(index);
    }

    /** 关联审核任务是否已全部 approved（REVIEWING 状态下显式触发回写前的自动推进） */
    private boolean allReviewsApproved(FeedbackFlow flow) {
        List<Long> taskIds = FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds());
        if (taskIds.isEmpty()) {
            return false;
        }
        var tasks = reviewTaskAdapter.listByIds(taskIds);
        if (tasks.isEmpty()) {
            return false;
        }
        boolean anyApproved = false;
        for (var task : tasks) {
            if (!ReviewTaskAdapter.isApproved(task)) {
                return false;
            }
            anyApproved = true;
        }
        return anyApproved;
    }

    /**
     * 校验 FAILED 流是否存在「已批准审核任务」（writeback 契约前置：至少一条建议已批准）。
     * 分析失败流（analyze_status != REVIEW_CREATED）或 review_task_ids 为空时返回 false，
     * 用于阻止分析失败流被 writeback 误置 CLOSED；APPROVED/WRITTEN_BACK 流自带批准语义不经过此闸门。
     */
    private boolean hasApprovedReview(FeedbackFlow flow) {
        // 分析未到达 REVIEW_CREATED（analyze/classify 失败或建议为空）→ 无批准语义
        if (!FeedbackAnalyzeStatus.REVIEW_CREATED.getValue().equals(flow.getAnalyzeStatus())) {
            return false;
        }
        List<Long> taskIds = FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds());
        if (taskIds.isEmpty()) {
            return false;
        }
        var tasks = reviewTaskAdapter.listByIds(taskIds);
        for (var task : tasks) {
            if (ReviewTaskAdapter.isApproved(task)) {
                return true;
            }
        }
        return false;
    }
}

