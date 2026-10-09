package com.agenthub.ai.rcagent.retrieval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 双重知识库融合检索-质量观测日志（rcagent_retrieval_log）。
 *
 * <p>记录单次融合检索的质量观测数据（FR-007）：查询要素/各路命中/冷启动/耗时/上下文规模。
 * JSON 复合列（query_elements_json/matched|missing_elements_json/channel_hit_json）
 * 以 TEXT 存 JSON（对齐 fastjson2 风格）；question 仅允许截断输出，禁止明文全量（AGENTS.md 5.3）。</p>
 */
@Data
@TableName(value = "rcagent_retrieval_log")
public class RcagentRetrievalLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID（BR-001 显式路由维度） */
    private String dsId;

    /** 关联工作流会话 thread_id */
    private String threadId;

    /** 原始问题（落库建议截断，查询仅截断展示） */
    private String question;

    /** 检索要素 JSON（time_range/dimensions/metrics/entities/concepts/keywords） */
    private String queryElementsJson;

    /** 命中要素清单 JSON 数组 */
    private String matchedElementsJson;

    /** 未命中要素清单 JSON 数组 */
    private String missingElementsJson;

    /** 各路命中统计 JSON（schema_tables/schema_columns/templates/biz_links/relations/metrics/concepts/wiki_docs） */
    private String channelHitJson;

    /** 输出上下文条目数 */
    private Integer contextItemCount;

    /** 上下文 token 估算 */
    private Integer contextTokenEstimate;

    /** 是否冷启动信号 0/1 */
    private Integer coldStart;

    /** 请求是否允许冷生成降级 0/1 */
    private Integer allowColdStart;

    /** 命中的知识库 B 空间名 */
    private String spaceUsed;

    /** 知识库 B 是否不可用被降级 0/1 */
    private Integer kbBUnavailable;

    /** 本次是否启用 Rerank 0/1 */
    private Integer rerankEnabled;

    /** 单次检索耗时（毫秒） */
    private Integer latencyMs;

    /** P2：命中正确 SQL 模板 id（反馈闭环回填预留） */
    private Long matchedTemplateId;

    /** P2：是否命中正确 SQL 0/1（反馈闭环回填预留） */
    private Integer matchedFlag;

    /** 创建时间 */
    private Date createTime;
}
