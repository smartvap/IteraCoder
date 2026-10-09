package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbSqlTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * kb_sql_template SQL 模板 Mapper。
 */
@Mapper
public interface KbSqlTemplateMapper extends BaseMapper<KbSqlTemplate> {

    /**
     * 结构变更/软删除反查：涉及表 JSON 文本包含指定表名的模板（非 deprecated），供影响分析。
     * JSON 文本按字符串元素匹配（%"table"%），避免把 LIKE 通配注入。
     */
    List<KbSqlTemplate> selectImpactedByTable(@Param("dsId") String dsId,
                                              @Param("tableName") String tableName);

    /**
     * SQL 模板检索召回（接口21）。
     */
    List<KbSqlTemplate> selectRetrievalTemplates(@Param("dsId") String dsId,
                                                 @Param("bizAction") String bizAction,
                                                 @Param("questionKeyword") String questionKeyword,
                                                 @Param("tableName") String tableName,
                                                 @Param("minConfidence") BigDecimal minConfidence,
                                                 @Param("topK") int topK);
}
