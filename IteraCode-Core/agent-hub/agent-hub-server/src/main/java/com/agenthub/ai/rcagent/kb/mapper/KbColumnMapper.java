package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbColumn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_column 字段字典 Mapper。
 */
@Mapper
public interface KbColumnMapper extends BaseMapper<KbColumn> {

    /**
     * Schema 检索列候选（接口20）。
     *
     * @param qualifiedTableNames 精确「库.表」限定名候选（如 DB_A.T1），传 UPPER 口径；跨库同名表按 schema 精确区分
     */
    List<KbColumn> selectRetrievalColumns(@Param("dsId") String dsId,
                                          @Param("keyword") String keyword,
                                          @Param("tableNames") List<String> tableNames,
                                          @Param("qualifiedTableNames") List<String> qualifiedTableNames,
                                          @Param("semanticTags") List<String> semanticTags,
                                          @Param("includeDraft") boolean includeDraft,
                                          @Param("topK") int topK);
}
