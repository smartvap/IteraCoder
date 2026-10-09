package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbRelation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_relation 表关系 Mapper。
 */
@Mapper
public interface KbRelationMapper extends BaseMapper<KbRelation> {

    /**
     * 表关系检索召回（接口23）：给定表对/单表查询可 JOIN 关系，默认高置信。
     */
    List<KbRelation> selectRetrievalRelations(@Param("dsId") String dsId,
                                              @Param("tableA") String tableA,
                                              @Param("tableB") String tableB,
                                              @Param("relationType") String relationType,
                                              @Param("topK") int topK);
}
