package com.agenthub.ai.rcagent.kbcrawler.scheduler;

import com.agenthub.ai.rcagent.kbcrawler.config.KbCrawlerProperties;
import com.agenthub.ai.rcagent.kbcrawler.constant.TaskType;
import com.agenthub.ai.rcagent.kbcrawler.constant.TriggerType;
import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlTask;
import com.agenthub.ai.rcagent.kbcrawler.service.KbCrawlerTaskService;
import com.agenthub.ai.rcagent.kbcrawler.service.KbRuntimeCollectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采集任务定时调度器（FR-006，A-5 调度与任务运维）。
 *
 * <p>cron 到点调度：以 fixed-delay 为扫描步长遍历 kb_crawl_task 中 {@code pause_flag=0}
 * 且非 running 的任务；每任务按生效 cron（{@code schedule_cron} 为空 → 配置默认
 * {@code agenthub.kbcrawler.scheduler.default-cron}）计算下次到点时间，
 * 到点（{@code next_run_time IS NULL} 视为到点，一次性自愈）时经
 * {@link KbCrawlerTaskService#claimDueTrigger} CAS 抢占推进 {@code next_run_time}，
 * 抢占成功才触发；抢占失败（被手动触发/被其它扫描抢占）跳过不并发。
 * 调度开关受 {@code agenthub.kbcrawler.scheduler.enabled} 控制。
 * {@code min-interval-ms} 默认 0 = 频率完全由 cron 决定；&gt;0 时作为同任务触发最小间隔下限。
 * 启动类已标 @EnableScheduling，本模块不重复开启。</p>
 *
 * <p>运行时缓冲周期 flush（FR-004）：RUNTIME_COLLECT 任务 due 间隔可能较长，
 * SqlCaptureInterceptor 捕获事件若只等任务触发会积压在 {@code RuntimeEventBuffer}；
 * 这里以 runtime.flush-interval-ms 为周期，在有缓冲事件时对该数据源做一次 flush
 * （drain 缓冲→归一化聚类→写 module-004），保证本地采集链路及时闭环。</p>
 */
@Slf4j
@Component
public class KbCrawlerTaskScheduler {

    /** 生效 cron 解析后置：进程内缓存，避免每轮重复解析（表达式变体数量有限） */
    private final Map<String, CronExpression> cronCache = new ConcurrentHashMap<>();

    private final KbCrawlerProperties properties;
    private final KbCrawlerTaskService taskService;
    private final KbRuntimeCollectService runtimeCollectService;

    public KbCrawlerTaskScheduler(KbCrawlerProperties properties,
                                  KbCrawlerTaskService taskService,
                                  KbRuntimeCollectService runtimeCollectService) {
        this.properties = properties;
        this.taskService = taskService;
        this.runtimeCollectService = runtimeCollectService;
    }

    @Scheduled(fixedDelayString = "${agenthub.kbcrawler.scheduler.fixed-delay-ms:60000}")
    public void scheduleCrawl() {
        if (properties.getScheduler() != null && !properties.getScheduler().isEnabled()) {
            return;
        }
        long minInterval = properties.getScheduler() == null ? 0L : properties.getScheduler().getMinIntervalMs();
        Date now = new Date();
        List<KbCrawlTask> tasks = taskService.listSchedulable();
        for (KbCrawlTask task : tasks) {
            try {
                // 暂停任务跳过（SQL 已过滤，二次判断作双保险）
                if (Integer.valueOf(1).equals(task.getPauseFlag())) {
                    continue;
                }
                CronExpression cron = effectiveCron(task.getScheduleCron());
                if (cron == null) {
                    continue;
                }
                Date nextRunTime = task.getNextRunTime();
                // next_run_time 为空 = 尚未建立调度基线（遗留行/首次登记），视为到点
                boolean due = nextRunTime == null || !nextRunTime.after(now);
                if (!due) {
                    continue;
                }
                Date next = nextRunTimeAfter(cron, now, task.getLastRunTime(), minInterval);
                if (next == null) {
                    continue;
                }
                // CAS 抢占：抢占失败说明任务被其它路径接管（手动触发/其它扫描），跳过不并发
                if (!taskService.claimDueTrigger(task.getId(), next, now)) {
                    continue;
                }
                log.info("kbcrawler: 调度触发 dsId={} type={} id={} nextRunTime={}",
                        task.getDsId(), task.getTaskType(), task.getId(), next);
                taskService.triggerFromScheduler(task, TriggerType.SCHEDULE.getValue());
            } catch (Exception e) {
                // 单任务调度失败不阻塞其他（单源失败不影响其他 ds_id）
                log.warn("kbcrawler: 调度触发失败 id={}: {}", task.getId(), e.getMessage());
            }
        }
    }

    /**
     * 计算生效 cron：行内为空/空白 → 配置默认；解析失败 → WARN + 回退默认（不抛异常、不阻断其它任务）。
     *
     * @return 可解析的 CronExpression；连默认表达式都不可用时返回 null（该任务本轮跳过）
     */
    private CronExpression effectiveCron(String scheduleCron) {
        String expr = (scheduleCron == null || scheduleCron.isBlank()) ? defaultCron() : scheduleCron.trim();
        CronExpression parsed = parseCached(expr);
        if (parsed != null) {
            return parsed;
        }
        String fallback = defaultCron();
        if (fallback.equals(expr)) {
            return null;
        }
        log.warn("kbcrawler: 任务 schedule_cron 非法，本次回退默认表达式");
        return parseCached(fallback);
    }

    /** 进程内缓存解析（解析失败返回 null） */
    private CronExpression parseCached(String expr) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        CronExpression cached = cronCache.get(expr);
        if (cached != null) {
            return cached;
        }
        try {
            CronExpression parsed = CronExpression.parse(expr);
            cronCache.put(expr, parsed);
            return parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 配置默认表达式（缺失时内置兜底） */
    private String defaultCron() {
        if (properties.getScheduler() == null) {
            return "0 * * * * ?";
        }
        String cron = properties.getScheduler().getDefaultCron();
        return cron == null || cron.isBlank() ? "0 * * * * ?" : cron.trim();
    }

    /**
     * 下次到点时间：{@code base = cron.next(now)}；
     * 若 {@code minIntervalMs > 0} 且 {@code lastRunTime != null} 且 base 早于下限 → 返回下限（下限保护）。
     *
     * <p>Spring 6 {@link CronExpression#next} 仅接受 Temporal，此处做 Date ↔ LocalDateTime 换算。</p>
     */
    private Date nextRunTimeAfter(CronExpression cron, Date now, Date lastRunTime, long minIntervalMs) {
        Date base = nextValidTimeAfter(cron, now);
        if (minIntervalMs > 0 && lastRunTime != null) {
            long floor = lastRunTime.getTime() + minIntervalMs;
            if (base == null || base.getTime() < floor) {
                return new Date(floor);
            }
        }
        return base;
    }

    /** 计算下一次到点（表达式永不匹配/无法计算 → null；由外层 try/catch 与 null 判定兜底） */
    private Date nextValidTimeAfter(CronExpression cron, Date now) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime next = cron.next(now.toInstant().atZone(zone).toLocalDateTime());
        return next == null ? null : Date.from(next.atZone(zone).toInstant());
    }

    /**
     * 运行时缓冲周期 flush：仅在通道启用、缓冲仍有事件时执行，
     * 触发间隔取 runtime.flush-interval-ms（默认 5000ms）。
     */
    @Scheduled(fixedDelayString = "${agenthub.kbcrawler.runtime.flush-interval-ms:5000}")
    public void flushRuntimeBuffers() {
        if (properties.getRuntime() == null || !properties.getRuntime().isEnabled()) {
            return;
        }
        if (!runtimeCollectService.hasPendingBufferedEvents()) {
            return;
        }
        List<KbCrawlTask> tasks = taskService.listSchedulable();
        for (KbCrawlTask task : tasks) {
            if (Integer.valueOf(1).equals(task.getPauseFlag())) {
                continue;
            }
            if (!TaskType.RUNTIME_COLLECT.getValue().equals(task.getTaskType())) {
                continue;
            }
            try {
                runtimeCollectService.flushClusters(task.getDsId());
            } catch (Exception e) {
                log.warn("kbcrawler: 运行时缓冲周期 flush 失败 dsId={}: {}",
                        task.getDsId(), e.getMessage());
            }
        }
    }
}
