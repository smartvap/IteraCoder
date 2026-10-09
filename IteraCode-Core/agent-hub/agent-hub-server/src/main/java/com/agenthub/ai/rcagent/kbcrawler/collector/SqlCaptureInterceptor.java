package com.agenthub.ai.rcagent.kbcrawler.collector;

import com.agenthub.ai.rcagent.kbcrawler.config.KbCrawlerProperties;
import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeSqlEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis 运行时 SQL 捕获拦截器（SqlCaptureInterceptor SDK，FR-004）。
 *
 * <p>捕获接入业务系统真实执行 SQL（MyBatis BoundSql，参数不取值只记类型个数），
 * 入 {@link RuntimeEventBuffer} 供运行时采集服务归一化聚类，再由 RUNTIME_COLLECT 任务/flush 写库。</p>
 *
 * <p>安全与存量保护（BR-003/AGENTS.md 5.x）：
 * <ul>
 *   <li>默认关闭：{@code agenthub.kbcrawler.runtime.interceptor-enabled=false}（@ConditionalOnProperty），
 *       避免影响接入业务系统性能；装配后也仅进程内内存流转，<b>不本地持久化明文参数</b>；</li>
 *   <li>SQL 文本仅入内存缓冲，日志截断；paramBrief 只记录类型+个数；</li>
 *   <li>SDK 由接入方配置启用，MVP 以进程内缓冲 + RUNTIME_COLLECT 消费为落点（跨进程上报端点见 api-contract）。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class})
})
@Component
@ConditionalOnProperty(prefix = "agenthub.kbcrawler.runtime", name = "interceptor-enabled", havingValue = "true")
public class SqlCaptureInterceptor implements Interceptor {

    private final RuntimeEventBuffer eventBuffer;
    private final KbCrawlerProperties properties;

    public SqlCaptureInterceptor(RuntimeEventBuffer eventBuffer, KbCrawlerProperties properties) {
        this.eventBuffer = eventBuffer;
        this.properties = properties;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object target = invocation.getTarget();
        try {
            captureIfPossible(invocation, target);
        } catch (Exception e) {
            // 捕获失败不影响业务 SQL 执行（非侵入，尽力而为）
            log.debug("kbcrawler: 运行时 SQL 捕获失败(忽略): {}", e.getMessage());
        }
        return invocation.proceed();
    }

    private void captureIfPossible(Invocation invocation, Object target) {
        if (!(target instanceof Executor executor)) {
            return;
        }
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args.length > 1 ? args[1] : null;
        BoundSql boundSql;
        if (args.length >= 6 && args[5] instanceof BoundSql b) {
            boundSql = b;
        } else {
            boundSql = ms.getBoundSql(parameter);
        }
        RuntimeSqlEventDTO event = new RuntimeSqlEventDTO();
        event.setSql(boundSql.getSql());
        event.setParamBrief(paramBrief(boundSql));
        event.setCostMs(0L);
        event.setBizContext(java.util.Map.of(
                "dao", ms.getId(),
                "thread", Thread.currentThread().getName()));
        event.setOccurredAt(Instant.now().toString());
        eventBuffer.offer(event);
    }

    /** 参数摘要：只记类型+个数，禁值（AGENTS.md 5.x） */
    private String paramBrief(BoundSql boundSql) {
        List<String> briefs = new ArrayList<>();
        try {
            var params = boundSql.getParameterMappings();
            if (params == null) {
                return null;
            }
            for (var pm : params) {
                if (pm.getJavaType() != null) {
                    briefs.add(pm.getJavaType().getSimpleName() + ":" + 1);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return briefs.isEmpty() ? null : briefs.toString();
    }
}
