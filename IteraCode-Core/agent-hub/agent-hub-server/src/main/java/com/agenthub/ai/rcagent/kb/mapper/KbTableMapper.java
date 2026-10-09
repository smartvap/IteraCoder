package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbTable;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_table 表字典 Mapper。
 */
@Mapper
public interface KbTableMapper extends BaseMapper<KbTable> {

    /**
     * Schema 检索候选（接口20）：高置信过滤 + 关键词/标签/精确表名 + confidence 排序。
     *
     * @param includeDraft true 时不过滤 verified/status（审核场景），默认 false 仅 verified=1 AND active
     * @param qualifiedTableNames 精确「库.表」限定名候选（如 DB_A.T1），传 UPPER 口径；跨库同名表按 schema 精确区分
     */
    List<KbTable> selectRetrievalTables(@Param("dsId") String dsId,
                                        @Param("keyword") String keyword,
                                        @Param("tableNames") List<String> tableNames,
                                        @Param("qualifiedTableNames") List<String> qualifiedTableNames,
                                        @Param("semanticTags") List<String> semanticTags,
                                        @Param("includeDraft") boolean includeDraft,
                                        @Param("topK") int topK);
}
