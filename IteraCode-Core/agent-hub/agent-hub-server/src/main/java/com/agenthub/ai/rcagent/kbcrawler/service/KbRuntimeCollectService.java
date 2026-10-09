package com.agenthub.ai.rcagent.kbcrawler.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.rcagent.kbcrawler.adapter.KbStorageReviewAdapter;
import com.agenthub.ai.rcagent.kbcrawler.collector.MySqlPerfSchemaCollector;
import com.agenthub.ai.rcagent.kbcrawler.collector.OracleV$SqlCollector;
import com.agenthub.ai.rcagent.kbcrawler.collector.RuntimeEventBuffer;
import com.agenthub.ai.rcagent.kbcrawler.config.KbCrawlerProperties;
import com.agenthub.ai.rcagent.kbcrawler.constant.KbCrawlerConstant;
import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeReportDTO;
import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeSqlEventDTO;
import com.agenthub.ai.rcagent.kbcrawler.model.NormalizedSqlModel;
import com.agenthub.ai.rcagent.kbcrawler.model.SqlTemplateCluster;
import com.agenthub.ai.rcagent.kbcrawler.util.KbSqlNormalizer;
import com.agenthub.ai.rcagent.kbcrawler.vo.RuntimeReportResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时采集服务（FR-004）：消费 runtime/report 上报事件，
 * 脱敏→归一化（参数占位/大小写空格/敏感字面量剔除）→指纹→内存聚类 SqlTemplateCluster→
 * 批量经 {@link KbStorageReviewAdapter} 写 module-004 kb_sql_template / kb_relation（RUNTIME_JOIN）。
 *
 * <p>MVP 说明（对齐 data-model 运行时不落库/内存缓冲设计）：上报批次内聚合并于批尾 flush 写库，
 * RUNTIME_COLLECT 手动/定时 flush 兜底清空残余聚类；模板指纹在 module-004 内幂等，
 * 重复上报同一指纹时由 module-004 累加 freq，不会产生重复行。</p>
 *
 * <p>本地缓冲消费：SqlCaptureInterceptor 捕获的 SQL 事件先入 {@link RuntimeEventBuffer}，
 * RUNTIME_COLLECT 任务/定时 flush 先 {@link #drainBufferedEvents(String, String)} 取出缓冲事件
 * 归一化 merge 入聚类，再统一 flush 写库（进程内本地闭环；缓冲事件不带 ds_id，MVP 按
 * RUNTIME_COLLECT 目标数据源归属）。</p>
 *
 * <p>通道默认关闭（BR-003）：{@code agenthub.kbcrawler.runtime.enabled=false} 时上报返回 44004；
 * 上报事件禁携带参数明细/敏感值，服务端对含明文敏感字面量的事件拒收（fail-closed）。</p>
 */
@Slf4j
@Service
public class KbRuntimeCollectService {

    /** JOIN 字段证据提取：... JOIN B ... ON A.C1 = B.C2 ... */
    private static final Pattern JOIN_ON = Pattern.compile(
            "(?i)\\bjoin\\s+([A-Za-z0-9_$]+)\\s+[A-Za-z0-9_$]*\\s*on\\s+"
                    + "([A-Za-z0-9_$]+)\\.([A-Za-z0-9_$]+)\\s*=\\s*"
                    + "([A-Za-z0-9_$]+)\\.([A-Za-z0-9_$]+)");

    /** 缓冲为空/配置非法时的默认 drain 批量（未接线 report-batch-size 时兜底） */
    private static final int DEFAULT_DRAIN_BATCH = 200;

    /** 聚类缓存：dsId:指纹 → 聚类（MVP 内存聚合，flush 后清除；服务重启丢窗口可接受） */
    private final Map<String, SqlTemplateCluster> clusterCache = new ConcurrentHashMap<>();

    private final KbSqlNormalizer normalizer;
    private final KbStorageReviewAdapter adapter;
    private final KbCrawlerProperties properties;
    private final DataSourceRegistry dataSourceRegistry;
    private final OracleV$SqlCollector oracleV$SqlCollector;
    private final MySqlPerfSchemaCollector mySqlPerfSchemaCollector;
    private final RuntimeEventBuffer eventBuffer;

    public KbRuntimeCollectService(KbSqlNormalizer normalizer,
                                   KbStorageReviewAdapter adapter,
                                   KbCrawlerProperties properties,
                                   DataSourceRegistry dataSourceRegistry,
                                   OracleV$SqlCollector oracleV$SqlCollector,
                                   MySqlPerfSchemaCollector mySqlPerfSchemaCollector,
                                   RuntimeEventBuffer eventBuffer) {
        this.normalizer = normalizer;
        this.adapter = adapter;
        this.properties = properties;
        this.dataSourceRegistry = dataSourceRegistry;
        this.oracleV$SqlCollector = oracleV$SqlCollector;
        this.mySqlPerfSchemaCollector = mySqlPerfSchemaCollector;
        this.eventBuffer = eventBuffer;
    }

    /**
     * 接口6 运行时上报主入口：校验通道 → 归一化 → 内存聚类 → 批尾 flush 写库。
     */
    public RuntimeReportResultVO consumeRuntimeEvents(RuntimeReportDTO dto) {
        if (!properties.runtimeEnabled()) {
            throw new BusinessException(KbCrawlerConstant.RUNTIME_DISABLED,
                    "运行时采集通道未启用，请确认接入方配置");
        }
        if (dto.getDsId() == null || dto.getDsId().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空");
        }
        String dbType = resolveDbType(dto.getDsId());
        List<RuntimeSqlEventDTO> events = dto.getEvents();
        if (events == null || events.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "上报事件不能为空");
        }

        int received = 0;
        int rejected = 0;
        // 本批参与写库的指纹去重计数
        Map<String, NormalizedSqlModel> batchModels = new LinkedHashMap<>();
        for (RuntimeSqlEventDTO event : events) {
            received++;
            if (event.getSql() == null || event.getSql().isBlank()) {
                rejected++;
                continue;
            }
            NormalizedSqlModel model = normalizer.normalize(dto.getDsId(), dbType, event.getSql());
            if (model.isRejected()) {
                rejected++;
                continue;
            }
            model.setBizAction(inferBizAction(event));
            batchModels.put(model.getSqlFingerprint(), model);
        }

        // fail-closed（BR-001/44005）：整批含空 SQL / 明文敏感参数即拒收，不落库、不半写
        if (rejected > 0) {
            throw new BusinessException(KbCrawlerConstant.REPORT_INVALID,
                    "上报数据非法：含空 SQL 或明文敏感参数，已拒收");
        }

        // 内存聚类 merge
        for (NormalizedSqlModel model : batchModels.values()) {
            mergeCluster(model);
        }
        // 批尾 flush 本 ds 全部聚类（module-004 指纹幂等：同指纹仅一行，freq 累加）
        List<SqlTemplateCluster> flushed = flushAll(dto.getDsId(), dbType);

        int writtenRelations = 0;
        for (SqlTemplateCluster cluster : flushed) {
            if (cluster.getJoinEvidences() != null) {
                writtenRelations += cluster.getJoinEvidences().size();
            }
        }
        RuntimeReportResultVO vo = new RuntimeReportResultVO();
        vo.setReceived(received);
        vo.setUniqueFingerprints(batchModels.size());
        vo.setWrittenTemplates(flushed.size());
        vo.setWrittenRelations(writtenRelations);
        vo.setRejected(rejected);
        return vo;
    }

    /**
     * RUNTIME_COLLECT 任务：flush 内存残余聚类 + 本地缓冲事件（拦截器捕获）。
     */
    public RuntimeReportResultVO flushClusters(String dsId) {
        if (!properties.runtimeEnabled()) {
            throw new BusinessException(KbCrawlerConstant.RUNTIME_DISABLED,
                    "运行时采集通道未启用，请确认接入方配置");
        }
        String dbType = resolveDbType(dsId);
        // 先消费 SqlCaptureInterceptor 入缓冲的真实 SQL 事件（进程内本地闭环）
        int drained = drainBufferedEvents(dsId, dbType);
        List<SqlTemplateCluster> flushed = flushAll(dsId, dbType);
        int writtenRelations = 0;
        for (SqlTemplateCluster cluster : flushed) {
            if (cluster.getJoinEvidences() != null) {
                writtenRelations += cluster.getJoinEvidences().size();
            }
        }
        // 字典辅助（Oracle v$sql / MySQL performance_schema）作为可选通道：骨架返回空，权限不足降级不抛错
        try {
            if (dbType != null && dbType.startsWith("ORACLE")) {
                oracleV$SqlCollector.collectRecentSql(dsId, 50);
            } else if ("MYSQL".equalsIgnoreCase(dbType)
                    || "OCEANBASE_MYSQL".equalsIgnoreCase(dbType)) {
                mySqlPerfSchemaCollector.collectRecentSql(dsId, 50);
            }
        } catch (Exception e) {
            log.debug("kbcrawler: 字典辅助采集降级 dsId={}: {}", dsId, e.getMessage());
        }
        RuntimeReportResultVO vo = new RuntimeReportResultVO();
        vo.setReceived(drained);
        vo.setUniqueFingerprints(flushed.size());
        vo.setWrittenTemplates(flushed.size());
        vo.setWrittenRelations(writtenRelations);
        vo.setRejected(0);
        return vo;
    }

    /**
     * 本地缓冲是否仍有未消费事件（供周期 flush 判断是否值得执行）。
     */
    public boolean hasPendingBufferedEvents() {
        return eventBuffer.size() > 0;
    }

    /**
     * 从本地缓冲批量取事件并归一化 merge 入聚类。
     * report-batch-size 作为单次最大条数（配置未接线/非法时用默认值兜底）。
     *
     * @return 被接受（已 merge）的事件条数
     */
    private int drainBufferedEvents(String dsId, String dbType) {
        int batch = properties.getRuntime() == null || properties.getRuntime().getReportBatchSize() <= 0
                ? DEFAULT_DRAIN_BATCH : properties.getRuntime().getReportBatchSize();
        List<RuntimeSqlEventDTO> events = eventBuffer.drain(batch);
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        int rejected = 0;
        for (RuntimeSqlEventDTO event : events) {
            if (event == null || event.getSql() == null || event.getSql().isBlank()) {
                rejected++;
                continue;
            }
            NormalizedSqlModel model = normalizer.normalize(dsId, dbType, event.getSql());
            if (model == null || model.isRejected()) {
                rejected++;
                continue;
            }
            model.setBizAction(inferBizAction(event));
            mergeCluster(model);
            accepted++;
        }
        if (rejected > 0) {
            log.warn("kbcrawler: 缓冲事件拒收跳过 {} 条（空 SQL/敏感明文）dsId={}", rejected, dsId);
        }
        return accepted;
    }

    /** 归一化事件 merge 进内存聚类（同指纹 freq++） */
    private void mergeCluster(NormalizedSqlModel model) {
        String key = cacheKey(model.getDsId(), model.getSqlFingerprint());
        SqlTemplateCluster cluster = clusterCache.computeIfAbsent(key, k -> {
            SqlTemplateCluster c = new SqlTemplateCluster();
            c.setDsId(model.getDsId());
            c.setSqlFingerprint(model.getSqlFingerprint());
            c.setNormalizedSql(model.getNormalizedSql());
            c.setFreq(0);
            c.setAvgCostMs(0L);
            c.setInvolvedTables(model.getInvolvedTables());
            c.setBizAction(model.getBizAction());
            Date now = new Date();
            c.setFirstSeen(now);
            c.setLastSeen(now);
            return c;
        });
        synchronized (cluster) {
            cluster.setFreq(cluster.getFreq() == null ? 1 : cluster.getFreq() + 1);
            cluster.setLastSeen(new Date());
            if (model.getInvolvedTables() != null && !model.getInvolvedTables().isEmpty()) {
                cluster.setInvolvedTables(model.getInvolvedTables());
            }
            // JOIN 关系证据
            List<SqlTemplateCluster.JoinEvidence> joins =
                    extractJoinEvidence(cluster.getNormalizedSql());
            if (!joins.isEmpty()) {
                cluster.setJoinEvidences(joins);
            }
        }
    }

    /** flush 该 ds 全部聚类并清空，返回本次写出聚类（供响应统计） */
    private List<SqlTemplateCluster> flushAll(String dsId, String dbType) {
        List<SqlTemplateCluster> written = new ArrayList<>();
        String prefix = dsId + ":";
        List<String> keys = new ArrayList<>();
        clusterCache.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                keys.add(k);
            }
        });
        for (String key : keys) {
            SqlTemplateCluster cluster = clusterCache.remove(key);
            if (cluster == null) {
                continue;
            }
            try {
                String evidence = "runtime-collect;freq=" + cluster.getFreq();
                adapter.saveTemplate(dsId, dbType, cluster.getNormalizedSql(),
                        cluster.getBizAction(), cluster.getInvolvedTables(), cluster.getFreq(), evidence);
                if (cluster.getJoinEvidences() != null) {
                    for (SqlTemplateCluster.JoinEvidence join : cluster.getJoinEvidences()) {
                        List<List<String>> joinFields = new ArrayList<>();
                        joinFields.add(List.of(join.getTableA() + "." + join.getColumnA(),
                                join.getTableB() + "." + join.getColumnB()));
                        adapter.saveRelation(dsId, dbType, join.getTableA(), join.getColumnA(),
                                join.getTableB(), join.getColumnB(), "REFERENCE", joinFields, evidence);
                    }
                }
                written.add(cluster);
            } catch (Exception e) {
                log.warn("kbcrawler: 运行时聚类写库失败 dsId={}: {}", dsId, e.getMessage());
            }
        }
        return written;
    }

    private String cacheKey(String dsId, String fingerprint) {
        return dsId + ":" + fingerprint;
    }

    /** 从归一化 SQL 提取 JOIN 字段证据（近似：ON 左表=右表 字段） */
    private List<SqlTemplateCluster.JoinEvidence> extractJoinEvidence(String normalizedSql) {
        List<SqlTemplateCluster.JoinEvidence> result = new ArrayList<>();
        if (normalizedSql == null) {
            return result;
        }
        Matcher m = JOIN_ON.matcher(normalizedSql);
        while (m.find()) {
            SqlTemplateCluster.JoinEvidence e = new SqlTemplateCluster.JoinEvidence();
            e.setTableA(m.group(1).toUpperCase());
            e.setColumnA(m.group(3).toUpperCase());
            e.setTableB(m.group(4).toUpperCase());
            e.setColumnB(m.group(5).toUpperCase());
            e.setRelationType("REFERENCE");
            result.add(e);
        }
        return result;
    }

    /** 推断业务动作（biz_action）：来自 biz_context.dao 方法名（截断去包名），无则 null */
    private String inferBizAction(RuntimeSqlEventDTO event) {
        if (event.getBizContext() instanceof Map<?, ?> ctx) {
            Object dao = ctx.get("dao");
            if (dao != null) {
                String s = String.valueOf(dao);
                int dot = s.lastIndexOf('.');
                String method = dot >= 0 && dot + 1 < s.length() ? s.substring(dot + 1) : s;
                return method.length() > 100 ? method.substring(0, 100) : method;
            }
        }
        return null;
    }

    private String resolveDbType(String dsId) {
        try {
            DsConfigView view = dataSourceRegistry.getConfig(dsId);
            return view == null ? "MYSQL" : view.getDbType();
        } catch (DbAccessException e) {
            if (e.getCode() == DbAccessErrorCode.UNKNOWN_DS.getCode()) {
                throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源不存在或未注册");
            }
            return "MYSQL";
        }
    }
}
