package com.agenthub.ai.rcagent.attribution.service;

import com.agenthub.ai.rcagent.attribution.dto.AttributionReviewPageQuery;
import com.agenthub.ai.rcagent.attribution.dto.AttributionTaskPageQuery;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionFactor;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionNode;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionReviewLog;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionTask;
import com.agenthub.ai.rcagent.attribution.exception.AttributionException;
import com.agenthub.ai.rcagent.attribution.mapper.RcagentAttributionFactorMapper;
import com.agenthub.ai.rcagent.attribution.mapper.RcagentAttributionNodeMapper;
import com.agenthub.ai.rcagent.attribution.mapper.RcagentAttributionQueryLogMapper;
import com.agenthub.ai.rcagent.attribution.mapper.RcagentAttributionReviewLogMapper;
import com.agenthub.ai.rcagent.attribution.mapper.RcagentAttributionTaskMapper;
import com.agenthub.ai.rcagent.attribution.model.AnomalyResult;
import com.agenthub.ai.rcagent.attribution.vo.AttributionReviewItemVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionTaskVO;
import com.agenthub.ai.base.common.PageResult;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 归因运行态持久化（AttributionTaskStore）：task/node/factor/query_log/review_log 读写 + 业务 ID 生成。
 *
 * <p>全部逻辑删除 is_deleted 收敛；业务 ID（task_id/node_id/factor_id/query_id/review_id）由服务生成，
 * 便于跨模块/日志追溯（对齐 data-model §0/§2 主键策略）。</p>
 */
@Service
public class AttributionTaskStore {

    private final RcagentAttributionTaskMapper taskMapper;
    private final RcagentAttributionNodeMapper nodeMapper;
    private final RcagentAttributionFactorMapper factorMapper;
    private final RcagentAttributionQueryLogMapper queryLogMapper;
    private final RcagentAttributionReviewLogMapper reviewLogMapper;

    public AttributionTaskStore(RcagentAttributionTaskMapper taskMapper,
                                RcagentAttributionNodeMapper nodeMapper,
                                RcagentAttributionFactorMapper factorMapper,
                                RcagentAttributionQueryLogMapper queryLogMapper,
                                RcagentAttributionReviewLogMapper reviewLogMapper) {
        this.taskMapper = taskMapper;
        this.nodeMapper = nodeMapper;
        this.factorMapper = factorMapper;
        this.queryLogMapper = queryLogMapper;
        this.reviewLogMapper = reviewLogMapper;
    }

    // ============ 业务 ID 生成 ============

    public String nextTaskId() {
        return "attr_" + timeStamp() + random6();
    }

    public String nextNodeId(String taskId, int seq) {
        return "attrnode_" + taskId + "_" + seq;
    }

    public String nextFactorId() {
        return "attrf_" + timeStamp() + random6();
    }

    public String nextQueryId() {
        return "attrq_" + timeStamp() + random6();
    }

    public String nextReviewId() {
        return "attrrv_" + timeStamp() + random6();
    }

    private String timeStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    private String random6() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000));
    }

    // ============ Task ============

    public RcagentAttributionTask findTask(String taskId) {
        return taskMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RcagentAttributionTask>()
                .eq(RcagentAttributionTask::getTaskId, taskId)
                .eq(RcagentAttributionTask::getIsDeleted, 0));
    }

    public void insertTask(RcagentAttributionTask task) {
        taskMapper.insert(task);
    }

    public void updateTask(RcagentAttributionTask task) {
        taskMapper.updateById(task);
    }

    /** 归因历史分页（接口4），records 转 VO（不含大 JSON 详情） */
    public PageResult pageTasks(AttributionTaskPageQuery query) {
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<RcagentAttributionTask> page = taskMapper.selectPageTasks(
                new Page<>(query.page(), query.pageSize()), query);
        List<AttributionTaskVO> records = new ArrayList<>(page.getRecords().size());
        for (RcagentAttributionTask task : page.getRecords()) {
            records.add(AttributionTaskVO.from(task, anomalyDirection(task.getAnomalyJson())));
        }
        return new PageResult(page.getTotal(), records);
    }

    public List<RcagentAttributionTask> listReviewPendingConclusionTasks(String dsId, String taskId) {
        return taskMapper.selectReviewPendingTasks(blankToNull(dsId), blankToNull(taskId));
    }

    // ============ Node ============

    public List<RcagentAttributionNode> listNodesByTask(String taskId) {
        return nodeMapper.selectByTaskOrdered(taskId);
    }

    public int nextNodeSeq(String taskId) {
        List<RcagentAttributionNode> nodes = listNodesByTask(taskId);
        return nodes.isEmpty() ? 1 : nodes.size() + 1;
    }

    public void insertNode(RcagentAttributionNode node) {
        nodeMapper.insert(node);
    }

    public void updateNode(RcagentAttributionNode node) {
        nodeMapper.updateById(node);
    }

    /** 软删 layer > resetLayer 的 node 及关联 factor/query（cancel/reset 回退，FR-008） */
    public void softDeleteNodesDeeperThan(String taskId, int resetLayer) {
        List<RcagentAttributionNode> deeper = nodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RcagentAttributionNode>()
                        .eq(RcagentAttributionNode::getTaskId, taskId)
                        .eq(RcagentAttributionNode::getIsDeleted, 0)
                        .gt(RcagentAttributionNode::getLayer, resetLayer));
        if (deeper.isEmpty()) {
            return;
        }
        List<String> nodeIds = new ArrayList<>();
        for (RcagentAttributionNode node : deeper) {
            nodeIds.add(node.getNodeId());
            nodeMapper.update(null, new LambdaUpdateWrapper<RcagentAttributionNode>()
                    .eq(RcagentAttributionNode::getId, node.getId()).set(RcagentAttributionNode::getIsDeleted, 1));
        }
        softDeleteFactorAndQueryByNodeIds(taskId, nodeIds);
    }

    private void softDeleteFactorAndQueryByNodeIds(String taskId, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return;
        }
        factorMapper.update(null, new LambdaUpdateWrapper<RcagentAttributionFactor>()
                .eq(RcagentAttributionFactor::getTaskId, taskId)
                .in(RcagentAttributionFactor::getNodeId, nodeIds)
                .set(RcagentAttributionFactor::getIsDeleted, 1));
        queryLogMapper.update(null, new LambdaUpdateWrapper<RcagentAttributionQueryLog>()
                .eq(RcagentAttributionQueryLog::getTaskId, taskId)
                .in(RcagentAttributionQueryLog::getNodeId, nodeIds)
                .set(RcagentAttributionQueryLog::getIsDeleted, 1));
    }

    // ============ Factor ============

    public void insertFactor(RcagentAttributionFactor factor) {
        factorMapper.insert(factor);
    }

    public void updateFactor(RcagentAttributionFactor factor) {
        factorMapper.updateById(factor);
    }

    public List<RcagentAttributionFactor> listFactorsByTask(String taskId) {
        return factorMapper.selectByTaskOrdered(taskId);
    }

    public List<RcagentAttributionFactor> listFactorsByNode(String nodeId) {
        return factorMapper.selectByNodeOrdered(nodeId);
    }

    /** 软删任务全部因子（reset_path/打回整层时使用） */
    public void softDeleteFactorsByNode(String taskId, String nodeId) {
        factorMapper.update(null, new LambdaUpdateWrapper<RcagentAttributionFactor>()
                .eq(RcagentAttributionFactor::getTaskId, taskId)
                .eq(RcagentAttributionFactor::getNodeId, nodeId)
                .set(RcagentAttributionFactor::getIsDeleted, 1));
    }

    /** 软删单个 node（审核打回跳过该层时使用） */
    public void softDeleteNode(String taskId, String nodeId) {
        nodeMapper.update(null, new LambdaUpdateWrapper<RcagentAttributionNode>()
                .eq(RcagentAttributionNode::getTaskId, taskId)
                .eq(RcagentAttributionNode::getNodeId, nodeId)
                .set(RcagentAttributionNode::getIsDeleted, 1));
    }

    // ============ QueryLog ============

    public void insertQueryLog(RcagentAttributionQueryLog queryLog) {
        queryLogMapper.insert(queryLog);
    }

    public void updateQueryLog(RcagentAttributionQueryLog queryLog) {
        queryLogMapper.updateById(queryLog);
    }

    public List<RcagentAttributionQueryLog> listQueryLogsByTask(String taskId) {
        return queryLogMapper.selectByTaskOrdered(taskId);
    }

    public Map<String, RcagentAttributionQueryLog> indexQueryLogsByTask(String taskId) {
        List<RcagentAttributionQueryLog> list = listQueryLogsByTask(taskId);
        return list.stream().collect(Collectors.toMap(RcagentAttributionQueryLog::getQueryId, Function.identity()));
    }

    public List<RcagentAttributionQueryLog> listReviewPendingQueries(String dsId, String taskId) {
        return queryLogMapper.selectReviewPendingQueries(blankToNull(dsId), blankToNull(taskId));
    }

    /** 软删某 node 的归因查询审计（审核打回/回退时使用） */
    public void softDeleteQueryLogsByNode(String taskId, String nodeId) {
        queryLogMapper.update(null, new LambdaUpdateWrapper<RcagentAttributionQueryLog>()
                .eq(RcagentAttributionQueryLog::getTaskId, taskId)
                .eq(RcagentAttributionQueryLog::getNodeId, nodeId)
                .set(RcagentAttributionQueryLog::getIsDeleted, 1));
    }

    // ============ ReviewLog ============

    public void insertReviewLog(RcagentAttributionReviewLog reviewLog) {
        reviewLogMapper.insert(reviewLog);
    }

    public List<RcagentAttributionReviewLog> listReviewLogsByTask(String taskId) {
        return reviewLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RcagentAttributionReviewLog>()
                .eq(RcagentAttributionReviewLog::getTaskId, taskId)
                .eq(RcagentAttributionReviewLog::getIsDeleted, 0)
                .orderByAsc(RcagentAttributionReviewLog::getReviewRound)
                .orderByAsc(RcagentAttributionReviewLog::getId));
    }

    /** 统计某任务指定对象在指定轮次后已发生的审核次数（结论打回轮次判定） */
    public int countConclusionRejectRounds(String taskId) {
        return Math.toIntExact(reviewLogMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RcagentAttributionReviewLog>()
                        .eq(RcagentAttributionReviewLog::getTaskId, taskId)
                        .eq(RcagentAttributionReviewLog::getIsDeleted, 0)
                        .eq(RcagentAttributionReviewLog::getReviewObject, "CONCLUSION")
                        .eq(RcagentAttributionReviewLog::getDecision, "REJECT")));
    }

    /**
     * 归因审核分页（接口5）：
     * 待审视图（pending_only=true，缺省）返回 QUERY 待审 + CONCLUSION 待审合并列表（量小，内存分页）；
     * 历史视图（pending_only=false）返回已审核留痕（join task 支持 ds_id 过滤，MP 分页）。
     */
    public PageResult pageReviews(AttributionReviewPageQuery query) {
        String reviewObject = blankToNull(query.getReviewObject());
        String decision = blankToNull(query.getDecision());
        if (query.pendingOnlyValue() && decision == null) {
            // 待审视图
            List<AttributionReviewItemVO> items = new ArrayList<>();
            if (reviewObject == null || "QUERY".equals(reviewObject)) {
                for (RcagentAttributionQueryLog q : listReviewPendingQueries(query.getDsId(), query.getTaskId())) {
                    if (query.getTaskId() != null && !query.getTaskId().isBlank()
                            && !query.getTaskId().equals(q.getTaskId())) {
                        continue;
                    }
                    AttributionReviewItemVO item = new AttributionReviewItemVO();
                    item.setReviewId(q.getQueryId());
                    item.setTaskId(q.getTaskId());
                    item.setReviewObject("QUERY");
                    item.setQueryId(q.getQueryId());
                    item.setRiskLevel(q.getRiskLevel());
                    item.setDsId(q.getDsId());
                    item.setSqlAbstract(q.getSqlAbstract());
                    item.setQueryType(q.getQueryType());
                    item.setStatus("PENDING");
                    item.setCreateTime(q.getCreateTime());
                    fillTaskBrief(item, q.getTaskId());
                    items.add(item);
                }
            }
            if (reviewObject == null || "CONCLUSION".equals(reviewObject)) {
                for (RcagentAttributionTask t : listReviewPendingConclusionTasks(query.getDsId(), query.getTaskId())) {
                    AttributionReviewItemVO item = new AttributionReviewItemVO();
                    item.setReviewId(t.getTaskId());
                    item.setTaskId(t.getTaskId());
                    item.setReviewObject("CONCLUSION");
                    item.setRiskLevel("HIGH");
                    item.setDsId(t.getDsId());
                    item.setMetricName(t.getMetricName());
                    item.setConclusionSummary(t.getConclusionSummary());
                    item.setStatus("PENDING");
                    item.setCreateTime(t.getCreateTime());
                    items.add(item);
                }
            }
            items.sort((a, b) -> compareDateDesc(a.getCreateTime(), b.getCreateTime()));
            return slice(items, query.page(), query.pageSize());
        }
        // 历史视图：MP 分页已审核留痕（decision 过滤）
        IPage<RcagentAttributionReviewLog> page = reviewLogMapper.selectPageReviews(
                new Page<>(query.page(), query.pageSize()), query);
        List<AttributionReviewItemVO> records = new ArrayList<>(page.getRecords().size());
        for (RcagentAttributionReviewLog reviewLog : page.getRecords()) {
            records.add(toReviewItem(reviewLog));
        }
        return new PageResult(page.getTotal(), records);
    }

    private PageResult slice(List<AttributionReviewItemVO> items, int page, int pageSize) {
        if (items == null || items.isEmpty()) {
            return new PageResult(0, new ArrayList<>());
        }
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, items.size());
        if (from >= items.size()) {
            return new PageResult(items.size(), new ArrayList<>());
        }
        return new PageResult(items.size(), new ArrayList<>(items.subList(from, to)));
    }

    private void fillTaskBrief(AttributionReviewItemVO item, String taskId) {
        RcagentAttributionTask task = findTask(taskId);
        if (task != null) {
            item.setMetricName(task.getMetricName());
        }
    }

    private AttributionReviewItemVO toReviewItem(RcagentAttributionReviewLog reviewLog) {
        AttributionReviewItemVO item = new AttributionReviewItemVO();
        item.setReviewId(reviewLog.getReviewId());
        item.setTaskId(reviewLog.getTaskId());
        item.setReviewObject(reviewLog.getReviewObject());
        item.setQueryId(reviewLog.getQueryId());
        item.setRiskLevel(reviewLog.getRiskLevel());
        item.setDecision(reviewLog.getDecision());
        item.setComment(reviewLog.getComment());
        item.setReviewerId(reviewLog.getReviewerId());
        item.setStatus("DONE");
        item.setCreateTime(reviewLog.getCreateTime());
        RcagentAttributionTask task = findTask(reviewLog.getTaskId());
        if (task != null) {
            item.setDsId(task.getDsId());
            item.setMetricName(task.getMetricName());
        }
        return item;
    }

    private int compareDateDesc(Date a, Date b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }

    // ============ 辅助 ============

    public static String anomalyDirection(String anomalyJson) {
        if (anomalyJson == null || anomalyJson.isBlank()) {
            return null;
        }
        try {
            AnomalyResult result = JSON.parseObject(anomalyJson, AnomalyResult.class);
            return result == null ? null : result.getDirection();
        } catch (Exception e) {
            return null;
        }
    }

    public static RcagentAttributionTask requireTask(RcagentAttributionTask task, String message) {
        if (task == null) {
            throw new AttributionException(
                    com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.TASK_NOT_FOUND_OR_STATE, message);
        }
        return task;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
