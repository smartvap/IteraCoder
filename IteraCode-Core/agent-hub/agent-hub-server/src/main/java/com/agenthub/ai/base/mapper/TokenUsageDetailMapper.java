package com.agenthub.ai.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agenthub.ai.base.entity.TokenUsageDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface TokenUsageDetailMapper extends BaseMapper<TokenUsageDetail> {

    /** 时间序列聚合 */
    @Select("""
        <script>
        SELECT
            <choose>
                <when test='granularity == "hour"'>DATE_FORMAT(request_time, '%Y-%m-%d %H:00')</when>
                <when test='granularity == "month"'>DATE_FORMAT(request_time, '%Y-%m')</when>
                <when test='granularity == "quarter"'>CONCAT(YEAR(request_time), '-Q', QUARTER(request_time))</when>
                <when test='granularity == "year"'>CAST(YEAR(request_time) AS CHAR)</when>
                <otherwise>DATE(request_time)</otherwise>
            </choose> AS period,
            COUNT(*) AS requests,
            COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
            COALESCE(SUM(completion_tokens), 0) AS completionTokens,
            COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens,
            COALESCE(SUM(total_duration_ms), 0) AS totalDurationMs
        FROM token_usage_detail WHERE status = 1
        <if test='start != null'>AND request_time &gt;= #{start}</if>
        <if test='end != null'>AND request_time &lt; #{end}</if>
        GROUP BY period ORDER BY period
        </script>
        """)
    List<Map<String, Object>> timeSeries(@Param("granularity") String granularity,
                                         @Param("start") Date start,
                                         @Param("end") Date end);

    /** 按用户排行 */
    @Select("""
        <script>
        SELECT COALESCE(u.name, CONCAT('用户', d.user_id)) AS label, d.user_id AS userId,
               COUNT(*) AS requests,
               COALESCE(SUM(d.prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(d.completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(d.prompt_tokens + d.completion_tokens), 0) AS totalTokens
        FROM token_usage_detail d
        LEFT JOIN tb_user u ON u.id = d.user_id
        WHERE d.user_id IS NOT NULL AND d.status = 1
        <if test='start != null'>AND d.request_time &gt;= #{start}</if>
        <if test='end != null'>AND d.request_time &lt; #{end}</if>
        GROUP BY d.user_id, u.name ORDER BY totalTokens DESC LIMIT 20
        </script>
        """)
    List<Map<String, Object>> rankByUser(@Param("start") Date start,
                                         @Param("end") Date end);

    /** 按 IP 排行 */
    @Select("""
        <script>
        SELECT ip_address AS label, COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM token_usage_detail WHERE status = 1
        <if test='start != null'>AND request_time &gt;= #{start}</if>
        <if test='end != null'>AND request_time &lt; #{end}</if>
        GROUP BY ip_address ORDER BY totalTokens DESC LIMIT 20
        </script>
        """)
    List<Map<String, Object>> rankByIp(@Param("start") Date start,
                                       @Param("end") Date end);

    /** 按模型分布 */
    @Select("""
        <script>
        SELECT model_name AS label, model_name AS modelName, COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM token_usage_detail WHERE status = 1
        <if test='start != null'>AND request_time &gt;= #{start}</if>
        <if test='end != null'>AND request_time &lt; #{end}</if>
        GROUP BY model_name ORDER BY totalTokens DESC
        </script>
        """)
    List<Map<String, Object>> distByModel(@Param("start") Date start,
                                           @Param("end") Date end);

    /** 按来源分布（chat/workflow/codegen） */
    @Select("""
        <script>
        SELECT source AS label, COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM token_usage_detail WHERE status = 1
        <if test='start != null'>AND request_time &gt;= #{start}</if>
        <if test='end != null'>AND request_time &lt; #{end}</if>
        GROUP BY source ORDER BY totalTokens DESC
        </script>
        """)
    List<Map<String, Object>> distBySource(@Param("start") Date start,
                                           @Param("end") Date end);

    /** 汇总概览 */
    @Select("""
        <script>
        SELECT COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens,
               COALESCE(SUM(total_duration_ms), 0) AS totalDurationMs,
               COUNT(DISTINCT ip_address) AS ipCount,
               COUNT(DISTINCT user_id) AS userCount,
               COUNT(DISTINCT model_name) AS modelCount
        FROM token_usage_detail WHERE status = 1
        <if test='start != null'>AND request_time &gt;= #{start}</if>
        <if test='end != null'>AND request_time &lt; #{end}</if>
        </script>
        """)
    Map<String, Object> summary(@Param("start") Date start,
                                 @Param("end") Date end);
}
