package com.agenthub.ai.rcagent.kbcrawler.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.rcagent.kbcrawler.adapter.KbStorageReviewAdapter;
import com.agenthub.ai.rcagent.kbcrawler.config.KbCrawlerProperties;
import com.agenthub.ai.rcagent.kbcrawler.constant.KbCrawlerConstant;
import com.agenthub.ai.rcagent.kbcrawler.constant.TaskStatus;
import com.agenthub.ai.rcagent.kbcrawler.constant.TaskType;
import com.agenthub.ai.rcagent.kbcrawler.constant.TriggerType;
import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlTask;
import com.agenthub.ai.rcagent.kbcrawler.mapper.KbCrawlTaskMapper;
import com.agenthub.ai.rcagent.kbcrawler.vo.KbCrawlScheduleVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.KbCrawlTaskStatusVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.CrawlTriggerResultVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 采集任务编排与调度服务（FR-006）：任务状态落库/暂停恢复/失败重跑/触发编排。
 *
 * <p>触发语义：任务行不存在时先登记 scheduled；触发时置 running 并异步执行编排，
 * 结束更新 last_run/last_result/run_count；running 任务禁止重复触发（44003）；
 * 已暂停任务手动触发被拒（44003，需先 resume）；pause 控制调度器跳过。</p>
 *
 * <p>并发说明：单实例使用固定线程池执行采集，同任务由数据库 running 状态防重入；
 * 任务间无共享可变状态，单源失败不影响其他（快速失败）。</p>
 */
@Slf4j
@Service
public class KbCrawlerTaskService {

    /** 内置默认调度表达式（配置缺失兜底，与 KbCrawlerProperties.Scheduler#defaultCron 默认值一致） */
    private static final String DEFAULT_CRON_FALLBACK = "0 * * * * ?";

    /** 调度时间回显格式（api-contract：yyyy-MM-dd HH:mm:ss） */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KbCrawlTaskMapper taskMapper;
    private final KbSchemaCrawlService schemaCrawlService;
    private final KbMapperParseService mapperParseService;
    private final KbRuntimeCollectService runtimeCollectService;
    private final KbChangeDetectService changeDetectService;
    private final KbStorageReviewAdapter adapter;
    private final KbCrawlerProperties properties;

    /** 采集执行线程池（单实例固定 2 线程；防并发脏写快照由任务行 running 状态保证） */
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public KbCrawlerTaskService(KbCrawlTaskMapper taskMapper,
                                KbSchemaCrawlService schemaCrawlService,
                                KbMapperParseService mapperParseService,
                                KbRuntimeCollectService runtimeCollectService,
                                KbChangeDetectService changeDetectService,
                                KbStorageReviewAdapter adapter,
                                KbCrawlerProperties properties) {
        this.taskMapper = taskMapper;
        this.schemaCrawlService = schemaCrawlService;
        this.mapperParseService = mapperParseService;
        this.runtimeCollectService = runtimeCollectService;
        this.changeDetectService = changeDetectService;
        this.adapter = adapter;
        this.properties = properties;
    }

    /**
     * 接口1 手动/调度触发：登记任务行（不存在时创建）→ running → 异步编排。
     */
    public CrawlTriggerResultVO trigger(String dsId, String taskType, String mode, String triggerType) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(), new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        TaskType type = TaskType.from(taskType);
        ThrowUtils.throwIf(type == null, new BusinessException(KbCrawlerConstant.TASK_TYPE_UNSUPPORTED, "task_type 非法"));
        // SCHEMA 触发 mode 校验
        if (type == TaskType.SCHEMA_FULL || type == TaskType.SCHEMA_INCR) {
            if (mode != null && !"FULL".equalsIgnoreCase(mode) && !"INCR".equalsIgnoreCase(mode)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "mode 非法");
            }
        }
        TriggerType trig = triggerType == null || triggerType.isBlank()
                ? TriggerType.MANUAL : parseTrigger(triggerType);

        // 数据源可用性 fail-fast
        schemaCrawlService.validateDs(dsId);

        KbCrawlTask task = ensureTaskRow(dsId, type);
        // running / 已暂停 不允许重复触发
        if (TaskStatus.RUNNING.getValue().equals(task.getStatus())) {
            throw new BusinessException(KbCrawlerConstant.TASK_STATE_CONFLICT, "任务已在运行中，请勿重复触发");
        }
        if (Integer.valueOf(1).equals(task.getPauseFlag())) {
            throw new BusinessException(KbCrawlerConstant.TASK_STATE_CONFLICT, "任务已暂停，请先恢复再触发");
        }
        if (!markRunningIfFree(task, trig.getValue())) {
            throw new BusinessException(KbCrawlerConstant.TASK_STATE_CONFLICT, "任务状态已变化，请重试");
        }

        CrawlTriggerResultVO vo = new CrawlTriggerResultVO();
        vo.setTaskId(task.getId());
        vo.setDsId(dsId);
        vo.setTaskType(taskType);
        vo.setStatus(TaskStatus.RUNNING.getValue());

        boolean fullMode = type == TaskType.SCHEMA_FULL && mode != null && "FULL".equalsIgnoreCase(mode);
        String evidence = "trigger=" + trig.getValue();
        Long taskId = task.getId();
        executor.submit(() -> executeInternal(dsId, type, fullMode, trig.getValue(), evidence, taskId));
        return vo;
    }

    /**
     * 调度器自动触发（低频定时，间隔/暂停过滤已由调度器完成）：抢占 running 后同步执行。
     * 抢占失败说明任务正被手动触发/其他调度路径执行，跳过不并发。
     */
    public void triggerFromScheduler(KbCrawlTask task, String triggerType) {
        TaskType type = TaskType.from(task.getTaskType());
        if (type == null) {
            return;
        }
        if (!markRunningIfFree(task, triggerType)) {
            return;
        }
        boolean fullMode = false; // 调度以增量为主
        String evidence = "trigger=" + triggerType;
        executeInternal(task.getDsId(), type, fullMode, triggerType, evidence, task.getId());
    }

    /**
     * 抢占任务为 running（条件：原 status 非 running）。成功返回 true；失败返回 false。
     *
     * <p>抢占成功即代表新一轮实际执行开始（手动/调度/失败重跑均会走抢占），
     * 因此在同一原子 update 中对 run_count 做 {@code run_count = run_count + 1} 递增，
     * 保证接口 2/3 返回的 runCount 反映真实累计运行次数（FR-006/A-5.3）。</p>
     */
    private boolean markRunningIfFree(KbCrawlTask task, String triggerType) {
        Date now = new Date();
        KbCrawlTask running = new KbCrawlTask();
        running.setStatus(TaskStatus.RUNNING.getValue());
        running.setTriggerType(triggerType);
        running.setLastRunTime(now);
        running.setLastResult("running");
        running.setLastError(null);
        running.setUpdateTime(now);
        int updated = taskMapper.update(running, new UpdateWrapper<KbCrawlTask>()
                .eq("id", task.getId())
                .ne("status", TaskStatus.RUNNING.getValue())
                .setSql("run_count = run_count + 1"));
        return updated > 0;
    }

    /**
     * 统一执行体：调对应采集编排并落任务状态（成功/失败摘要截断）。
     */
    private void executeInternal(String dsId, TaskType type, boolean fullMode,
                                 String triggerType, String evidence, Long taskId) {
        String lastResult = "success";
        String lastError = null;
        String remark = null;
        try {
            switch (type) {
                case SCHEMA_FULL, SCHEMA_INCR -> {
                    Map<String, Object> summary = fullMode || type == TaskType.SCHEMA_FULL
                            ? schemaCrawlService.crawlFull(dsId)
                            : schemaCrawlService.crawlIncremental(dsId);
                    remark = summary == null ? null : summary.toString();
                }
                case MAPPER_PARSE -> mapperParseService.parseAndApply(dsId);
                case RUNTIME_COLLECT -> {
                    if (!properties.runtimeEnabled()) {
                        remark = KbCrawlerConstant.REMARK_RUNTIME_DISABLED;
                    } else {
                        runtimeCollectService.flushClusters(dsId);
                    }
                }
                case CHANGE_DETECT -> changeDetectService.detectChanges(dsId, evidence);
                default -> {
                    lastResult = "failed";
                    lastError = "不支持的采集类型";
                }
            }
        } catch (BusinessException e) {
            lastResult = "failed";
            lastError = truncate(e.getMessage(), 1000);
        } catch (Exception e) {
            lastResult = "failed";
            lastError = truncate("采集异常", 1000);
            log.warn("kbcrawler: 任务执行失败 dsId={} type={} id={}: {}", dsId, type, taskId, e.getMessage());
        } finally {
            markFinished(taskId, lastResult, lastError, remark);
        }
    }

    private void markFinished(Long taskId, String lastResult, String lastError, String remark) {
        try {
            KbCrawlTask finish = new KbCrawlTask();
            finish.setStatus(lastResult.equals("failed") ? TaskStatus.FAILED.getValue()
                    : TaskStatus.SUCCESS.getValue());
            finish.setLastResult(lastResult);
            finish.setLastError(lastError);
            if (remark != null) {
                finish.setRemark(truncate(remark, 500));
            }
            finish.setUpdateTime(new Date());
            taskMapper.update(finish, new UpdateWrapper<KbCrawlTask>().eq("id", taskId));
        } catch (Exception e) {
            log.warn("kbcrawler: 任务状态更新失败 id={}: {}", taskId, e.getMessage());
        }
    }

    /** 登记任务行（不存在则创建 scheduled 行并设置 RUNTIME_COLLECT 默认 remark/暂停） */
    private KbCrawlTask ensureTaskRow(String dsId, TaskType type) {
        KbCrawlTask exist = taskMapper.selectOne(new QueryWrapper<KbCrawlTask>()
                .eq("ds_id", dsId)
                .eq("task_type", type.getValue())
                .last("LIMIT 1"));
        if (exist != null) {
            return exist;
        }
        Date now = new Date();
        KbCrawlTask row = new KbCrawlTask();
        row.setDsId(dsId);
        row.setTaskType(type.getValue());
        row.setTaskName(dsId + " " + type.getValue());
        row.setStatus(TaskStatus.SCHEDULED.getValue());
        row.setPauseFlag(type == TaskType.RUNTIME_COLLECT ? 1 : 0);
        row.setTriggerType(KbCrawlerConstant.TRIGGER_MANUAL);
        row.setRunCount(0);
        if (type == TaskType.RUNTIME_COLLECT) {
            row.setRemark(KbCrawlerConstant.REMARK_RUNTIME_DISABLED);
        }
        row.setCreateTime(now);
        row.setUpdateTime(now);
        taskMapper.insert(row);
        return row;
    }

    // ==================== 状态/列表 ====================

    /** 接口2 按 ds_id（可选 task_type）查询任务状态 */
    public List<KbCrawlTaskStatusVO> listStatus(String dsId, String taskType) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(), new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        QueryWrapper<KbCrawlTask> qw = new QueryWrapper<KbCrawlTask>()
                .eq("ds_id", dsId)
                .orderByAsc("task_type");
        if (taskType != null && !taskType.isBlank()) {
            qw.eq("task_type", taskType);
        }
        List<KbCrawlTask> tasks = taskMapper.selectList(qw);
        List<KbCrawlTaskStatusVO> vos = new ArrayList<>(tasks.size());
        for (KbCrawlTask t : tasks) {
            vos.add(toStatusVO(t));
        }
        return vos;
    }

    /** 接口3 分页任务列表（PageResult 与项目风格一致） */
    public PageResult pageList(String dsId, String status, Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null ? 20 : Math.max(1, Math.min(pageSize, 200));
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<KbCrawlTask> result = taskMapper.selectTaskList(new Page<>(p, ps), dsId, status);
        List<KbCrawlTaskStatusVO> records = new ArrayList<>(result.getRecords().size());
        for (KbCrawlTask t : result.getRecords()) {
            records.add(toStatusVO(t));
        }
        return new PageResult(result.getTotal(), records);
    }

    private KbCrawlTaskStatusVO toStatusVO(KbCrawlTask t) {
        KbCrawlTaskStatusVO vo = new KbCrawlTaskStatusVO();
        vo.setDsId(t.getDsId());
        vo.setTaskType(t.getTaskType());
        vo.setTaskName(t.getTaskName());
        vo.setStatus(t.getStatus());
        vo.setPauseFlag(t.getPauseFlag());
        vo.setLastRunTime(t.getLastRunTime() == null ? null : t.getLastRunTime().toString());
        vo.setLastResult(t.getLastResult());
        vo.setLastError(t.getLastError());
        vo.setRunCount(t.getRunCount() == null ? 0 : t.getRunCount());
        vo.setRemark(t.getRemark());
        return vo;
    }

    // ==================== 暂停/恢复 ====================

    /**
     * 接口4 按 ds_id 暂停全部采集任务（pause_flag=1；运行中任务允许跑完，仅标记后续调度跳过）。
     */
    public PauseResumeResult pause(String dsId) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(), new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        List<KbCrawlTask> tasks = taskMapper.selectList(new QueryWrapper<KbCrawlTask>()
                .eq("ds_id", dsId).eq("pause_flag", 0));
        int paused = 0;
        int runningIgnored = 0;
        for (KbCrawlTask t : tasks) {
            KbCrawlTask upd = new KbCrawlTask();
            upd.setId(t.getId());
            upd.setPauseFlag(1);
            upd.setUpdateTime(new Date());
            taskMapper.updateById(upd);
            paused++;
            if (TaskStatus.RUNNING.getValue().equals(t.getStatus())) {
                runningIgnored++;
            }
        }
        return new PauseResumeResult(paused, runningIgnored, 0);
    }

    /** 接口5 恢复被暂停任务（pause_flag=0, status=scheduled） */
    public PauseResumeResult resume(String dsId) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(), new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        List<KbCrawlTask> tasks = taskMapper.selectList(new QueryWrapper<KbCrawlTask>()
                .eq("ds_id", dsId).eq("pause_flag", 1));
        int resumed = 0;
        for (KbCrawlTask t : tasks) {
            KbCrawlTask upd = new KbCrawlTask();
            upd.setId(t.getId());
            upd.setPauseFlag(0);
            upd.setStatus(TaskStatus.SCHEDULED.getValue());
            upd.setUpdateTime(new Date());
            taskMapper.updateById(upd);
            resumed++;
        }
        return new PauseResumeResult(0, 0, resumed);
    }

    /** 供 Controller 构造 pause/resume 响应 */
    public record PauseResumeResult(int pausedCount, int runningIgnored, int resumedCount) {
    }

    private TriggerType parseTrigger(String v) {
        for (TriggerType t : TriggerType.values()) {
            if (t.getValue().equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "trigger_type 非法");
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 供调度器遍历候选任务（pause_flag=0 且非 running） */
    public List<KbCrawlTask> listSchedulable() {
        return taskMapper.selectSchedulable(null);
    }

    /**
     * 调度器到点抢占（CAS）：仅当任务仍可调度且已到点（或尚无调度基线）时推进 next_run_time。
     *
     * @return true=抢占成功（可安全触发）；false=被其它路径抢占/已暂停/运行中/未到点
     */
    public boolean claimDueTrigger(Long id, Date next, Date now) {
        if (id == null || next == null || now == null) {
            return false;
        }
        return taskMapper.claimDue(id, next, now) > 0;
    }

    // ==================== 自动登记（enable 钩子） ====================

    /**
     * 幂等登记 SCHEMA_INCR 任务行（供数据源启用钩子与调度配置接口复用）。
     *
     * <p>语义（FR-BE-010）：先按唯一键 {@code (ds_id, SCHEMA_INCR)} 查询，命中则**不插入、不更新任何业务字段**，
     * 唯一例外是当且仅当命中行 {@code next_run_time} 为 NULL 时补写默认调度（条件更新、幂等、
     * 不覆盖非空值、不写 schedule_cron、不触碰 status/pause_flag/trigger_type/run_count/remark/…）；
     * 未命中则复用既有 {@link #ensureTaskRow} 插入 scheduled 行，同样在 next_run_time 为空时补写。
     * 唯一键冲突（并发登记）吞并为"未新建"，不抛错。</p>
     *
     * @return created=true 表示本次新插入
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public EnsureResult ensureSchemaTask(String dsId) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        KbCrawlTask exist = findSchemaIncrTask(dsId);
        if (exist != null) {
            // FR-BE-010 唯一例外：命中行 next_run_time 为 NULL（如改造前经 POST /task/trigger 人工登记的行）
            // 时补写默认调度的下次到点，避免该行在调度器按「NULL=到点」被重复触发一次；
            // 采用条件更新（WHERE id=? AND next_run_time IS NULL），幂等、不覆盖非空值、不写 schedule_cron、
            // 不触碰 status/pause_flag/trigger_type/run_count/remark（FR-BE-016 护栏）
            if (exist.getNextRunTime() == null) {
                fillNextRunTimeIfAbsent(exist.getId(), exist.getScheduleCron());
            }
            return new EnsureResult(false, exist.getId());
        }
        KbCrawlTask row;
        try {
            row = ensureTaskRow(dsId, TaskType.SCHEMA_INCR);
        } catch (DuplicateKeyException e) {
            // 并发登记：唯一键 uk_ds_task_type 兜底再查，不抛出（登记幂等）
            KbCrawlTask fallback = findSchemaIncrTask(dsId);
            return new EnsureResult(false, fallback == null ? null : fallback.getId());
        }
        if (row != null && row.getNextRunTime() == null) {
            fillNextRunTimeIfAbsent(row.getId(), row.getScheduleCron());
        }
        return new EnsureResult(true, row == null ? null : row.getId());
    }

    /** 登记结果：created=true 表示本次新插入 */
    public record EnsureResult(boolean created, Long taskId) {
    }

    /**
     * 仅当 next_run_time 仍为 NULL 时补写下一次到点时间（条件更新，避免覆盖并发写入）。
     *
     * @param scheduleCron 行内原始 cron（null/空 → 配置默认）
     */
    private void fillNextRunTimeIfAbsent(Long id, String scheduleCron) {
        CronExpression cron = effectiveCron(scheduleCron);
        if (id == null || cron == null) {
            return;
        }
        Date now = new Date();
        Date next = nextTimeAfter(cron, now);
        if (next == null) {
            return;
        }
        UpdateWrapper<KbCrawlTask> uw = new UpdateWrapper<KbCrawlTask>()
                .eq("id", id)
                .isNull("next_run_time")
                .set("next_run_time", next)
                .set("update_time", now);
        taskMapper.update(null, uw);
    }

    // ==================== 调度配置读写 ====================

    /** 查询 SCHEMA_INCR 任务的调度配置（只读，不自动登记、无写副作用）。 */
    public KbCrawlScheduleVO getSchedule(String dsId) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        String defaultCron = defaultCron();
        KbCrawlTask row = findSchemaIncrTask(dsId);
        KbCrawlScheduleVO vo = new KbCrawlScheduleVO();
        vo.setDsId(dsId);
        vo.setTaskType(TaskType.SCHEMA_INCR.getValue());
        vo.setDefaultCron(defaultCron);
        if (row == null) {
            // 未登记：只读语义，不产生登记副作用
            vo.setEffectiveCron(defaultCron);
            vo.setRegistered(false);
            return vo;
        }
        vo.setTaskId(row.getId());
        vo.setScheduleCron(row.getScheduleCron());
        vo.setEffectiveCron(resolveEffectiveCron(row.getScheduleCron(), defaultCron));
        vo.setNextRunTime(formatTime(row.getNextRunTime()));
        vo.setStatus(row.getStatus());
        vo.setPauseFlag(row.getPauseFlag());
        vo.setLastRunTime(formatTime(row.getLastRunTime()));
        vo.setRunCount(row.getRunCount());
        vo.setRegistered(true);
        return vo;
    }

    /**
     * 写入 SCHEMA_INCR 任务的 schedule_cron 并重算 next_run_time。
     *
     * <p>空串/null 语义 = 清除自定义回落默认（落库 NULL）；任务行缺失时先幂等登记；
     * pause_flag=1 时不写 next_run_time（保持"恢复后到点"语义）；
     * 不触碰 status/pause_flag/run_count/trigger_type/remark。</p>
     */
    public KbCrawlScheduleVO updateSchedule(String dsId, String scheduleCron) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        String cron = (scheduleCron == null || scheduleCron.isBlank()) ? null : scheduleCron.trim();
        if (cron != null) {
            if (cron.length() > 64) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "schedule_cron 长度超限（≤64）");
            }
            if (!isValidCron(cron)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "schedule_cron 非法（需 Spring 6 段表达式：秒 分 时 日 月 周）");
            }
        }
        KbCrawlTask row = findSchemaIncrTask(dsId);
        if (row == null) {
            // 存量已 ACTIVE 数据源的补登记运维入口（与 enable 钩子同一实现）
            EnsureResult ensured = ensureSchemaTask(dsId);
            row = ensured.taskId() == null ? null : findSchemaIncrTask(dsId);
        }
        ThrowUtils.throwIf(row == null, new BusinessException(ErrorCode.PARAMS_ERROR, "采集任务登记失败，请稍后重试"));

        Date now = new Date();
        UpdateWrapper<KbCrawlTask> uw = new UpdateWrapper<KbCrawlTask>().eq("id", row.getId());
        uw.set("schedule_cron", cron);
        boolean paused = Integer.valueOf(1).equals(row.getPauseFlag());
        if (!paused) {
            CronExpression cronExp = effectiveCron(cron);
            Date next = cronExp == null ? null : nextTimeAfter(cronExp, now);
            if (next != null) {
                uw.set("next_run_time", next);
            }
        }
        uw.set("update_time", now);
        taskMapper.update(null, uw);
        // 回读组装（与 GET 同结构，便于前端保存后直接刷新）
        return getSchedule(dsId);
    }

    /** 按唯一键 (ds_id, SCHEMA_INCR) 查询任务行（命中 uk_ds_task_type 索引）。 */
    private KbCrawlTask findSchemaIncrTask(String dsId) {
        return taskMapper.selectOne(new QueryWrapper<KbCrawlTask>()
                .eq("ds_id", dsId)
                .eq("task_type", TaskType.SCHEMA_INCR.getValue())
                .last("LIMIT 1"));
    }

    /** 计算生效 cron：行内为空 → 配置默认；配置默认也为空 → 内置兜底。 */
    private String resolveEffectiveCron(String scheduleCron, String defaultCron) {
        if (scheduleCron != null && !scheduleCron.isBlank()) {
            return scheduleCron.trim();
        }
        return defaultCron == null || defaultCron.isBlank() ? DEFAULT_CRON_FALLBACK : defaultCron.trim();
    }

    /** 配置默认表达式（null 时回退内置默认）。 */
    private String defaultCron() {
        if (properties.getScheduler() == null) {
            return DEFAULT_CRON_FALLBACK;
        }
        String cron = properties.getScheduler().getDefaultCron();
        return cron == null || cron.isBlank() ? DEFAULT_CRON_FALLBACK : cron.trim();
    }

    /** 解析 cron（空/非法返回 null，不抛异常）。 */
    private CronExpression effectiveCron(String scheduleCron) {
        String expr = resolveEffectiveCron(scheduleCron, defaultCron());
        if (!isValidCron(expr)) {
            return null;
        }
        return CronExpression.parse(expr);
    }

    private boolean isValidCron(String expr) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        try {
            CronExpression.parse(expr.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String formatTime(Date date) {
        if (date == null) {
            return null;
        }
        return TIME_FORMATTER.format(date.toInstant().atZone(ZoneId.systemDefault()));
    }

    /**
     * 计算 cron 在 now 之后的下一次到点时间（Spring 6 {@link CronExpression#next} 仅接受 Temporal）。
     *
     * @return 下一次到点；表达式永不匹配/无法计算时返回 null
     */
    private Date nextTimeAfter(CronExpression cron, Date now) {
        if (cron == null || now == null) {
            return null;
        }
        try {
            ZoneId zone = ZoneId.systemDefault();
            LocalDateTime next = cron.next(now.toInstant().atZone(zone).toLocalDateTime());
            return next == null ? null : Date.from(next.atZone(zone).toInstant());
        } catch (RuntimeException e) {
            log.warn("kbcrawler: 调度表达式无法计算下次到点时间: {}", e.getMessage());
            return null;
        }
    }
}
