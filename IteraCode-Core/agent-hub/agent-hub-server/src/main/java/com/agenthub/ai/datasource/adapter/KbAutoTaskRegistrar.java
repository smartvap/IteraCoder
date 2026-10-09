package com.agenthub.ai.datasource.adapter;

import com.agenthub.ai.rcagent.kbcrawler.constant.TaskStatus;
import com.agenthub.ai.rcagent.kbcrawler.constant.TaskType;
import com.agenthub.ai.rcagent.kbcrawler.constant.TriggerType;
import com.agenthub.ai.rcagent.kbcrawler.service.KbCrawlerTaskService;
import com.agenthub.ai.rcagent.kbcrawler.vo.KbCrawlTaskStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 数据源启用成功后的采集任务兜底登记 + 立即触发（datasource → kbcrawler 的<b>唯一</b>交互层，brownfield）。
 *
 * <p>非致命：全流程 try/catch，任何异常均记 1 条 WARN 后静默返回，绝不向上抛出、
 * 绝不使 enable 事务 rollback-only、绝不改变 enable 返回码/响应体/审计结果。</p>
 *
 * <p><b>事务边界（NOT_SUPPORTED，修复立即触发在 enable 主链路失效）</b>：本方法被
 * {@code DsConfigManageService.enableInternal}（外层 {@code @Transactional}）调用，而
 * {@link KbCrawlerTaskService#ensureSchemaTask} 以 {@code REQUIRES_NEW} 在另一连接提交任务行；
 * MySQL 默认 REPEATABLE READ 下，外层 enable 事务的读视图建立更早，看不到该新行 —— 会导致
 * {@code listStatus} 判定不可触发而静默跳过，或 {@code trigger} 内 {@code ensureTaskRow} 先查后插
 * 撞 {@code uk_ds_task_type} 抛 {@code DuplicateKeyException}。故本方法标注
 * {@code Propagation.NOT_SUPPORTED} 显式挂起外层事务，使登记/判定/触发均在自动提交连接上执行：
 * 既能立即读到已提交的登记行（{@code ensureTaskRow} 命中、不重复插入），又不把外层 enable 事务
 * 标记 rollback-only（异常已被本方法吞并）。</p>
 *
 * <p>安全（AGENTS.md 5.2/5.4）：日志仅输出 dsId 与异常摘要（截断 ≤500 字符），
 * 禁止输出 password/readonly_password_ref/apiKey/token/JDBC 连接串内嵌账密。</p>
 */
@Slf4j
@Component
public class KbAutoTaskRegistrar {

    /** 异常摘要截断长度（脱敏要求） */
    private static final int MAX_SUMMARY_LENGTH = 500;

    private final KbCrawlerTaskService taskService;

    public KbAutoTaskRegistrar(KbCrawlerTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 启用数据源后幂等登记 SCHEMA_INCR 任务行，并在任务可调度时以 SCHEDULE 来源立即触发一次
     * （{@code trigger} 内部经既有线程池异步执行，不阻塞 enable）。
     *
     * <p>事务边界见类注释：以 {@code Propagation.NOT_SUPPORTED} 挂起外层 enable 事务，
     * 在自动提交连接上完成登记行读取与触发判定。</p>
     *
     * @param dsId 数据源实例 ID
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void registerAndTriggerAfterEnable(String dsId) {
        try {
            KbCrawlerTaskService.EnsureResult result = taskService.ensureSchemaTask(dsId);
            Long taskId = result == null ? null : result.taskId();
            boolean created = result != null && result.created();
            boolean triggered = false;
            // 立即触发条件：pause_flag == 0 且 status != 'running'（不打扰运行中任务 / 不复活人工暂停任务）
            // 注：不在此做 validateDs 前置连通性探测，连通性由采集执行时的既有 validateDs 判定
            if (taskId != null && isTriggerable(dsId)) {
                taskService.trigger(dsId, TaskType.SCHEMA_INCR.getValue(), null,
                        TriggerType.SCHEDULE.getValue());
                triggered = true;
            }
            log.info("kbcrawler: 数据源启用后自动登记采集任务 dsId={} taskId={} created={} triggered={}",
                    dsId, taskId, created, triggered);
        } catch (Exception e) {
            // 非致命：登记/触发失败不影响数据源启用主链路
            log.warn("kbcrawler: 数据源启用后自动登记/触发失败 dsId={}: {}", dsId, truncate(e.getMessage()));
        }
    }

    /** 任务是否处于可立即触发状态（pause_flag=0 且非 running） */
    private boolean isTriggerable(String dsId) {
        List<KbCrawlTaskStatusVO> rows = taskService.listStatus(dsId, TaskType.SCHEMA_INCR.getValue());
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        KbCrawlTaskStatusVO row = rows.get(0);
        return !Integer.valueOf(1).equals(row.getPauseFlag())
                && !TaskStatus.RUNNING.getValue().equals(row.getStatus());
    }

    /** 异常摘要截断（≤500 字符，禁敏感信息） */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_SUMMARY_LENGTH ? s.substring(0, MAX_SUMMARY_LENGTH) : s;
    }
}
