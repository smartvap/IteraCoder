package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbBusinessConcept;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_business_concept 业务概念 Mapper。
 */
@Mapper
public interface KbBusinessConceptMapper extends BaseMapper<KbBusinessConcept> {

    /**
     * 语义检索-概念召回（接口24）：关键词匹配概念名/定义/同义词，默认高置信。
     */
    List<KbBusinessConcept> selectRetrievalConcepts(@Param("dsId") String dsId,
                                                    @Param("keyword") String keyword,
                                                    @Param("spaceName") String spaceName,
                                                    @Param("topK") int topK);
}
