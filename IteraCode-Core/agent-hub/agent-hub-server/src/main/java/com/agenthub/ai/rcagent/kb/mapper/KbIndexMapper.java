package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbIndex;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_index 索引字典 Mapper。
 */
@Mapper
public interface KbIndexMapper extends BaseMapper<KbIndex> {

    /**
     * Schema 检索索引候选（接口20，索引通道）。
     *
     * <p>过滤与排序口径与 {@link KbColumnMapper#selectRetrievalColumns} 完全一致；
     * <b>唯一差异</b>：kb_index 无 {@code semantic_tags} 列，故本方法不设 {@code semanticTags} 参数，
     * keyword 仅匹配 {@code index_name}/{@code index_columns} 两列。</p>
     *
     * @param qualifiedTableNames 精确「库.表」限定名候选（如 DB_A.T1），传 UPPER 口径；跨库同名表按 schema 精确区分
     */
    List<KbIndex> selectRetrievalIndexes(@Param("dsId") String dsId,
                                         @Param("keyword") String keyword,
                                         @Param("tableNames") List<String> tableNames,
                                         @Param("qualifiedTableNames") List<String> qualifiedTableNames,
                                         @Param("includeDraft") boolean includeDraft,
                                         @Param("topK") int topK);
}
