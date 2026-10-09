package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbBizLink;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_biz_link 业务链路 Mapper。
 */
@Mapper
public interface KbBizLinkMapper extends BaseMapper<KbBizLink> {

    /**
     * 结构变更/软删除反查：related_tables JSON 文本包含指定表名的链路（非 deprecated）。
     */
    List<KbBizLink> selectImpactedByTable(@Param("dsId") String dsId,
                                          @Param("tableName") String tableName);

    /**
     * 业务链路检索召回（接口22）。
     */
    List<KbBizLink> selectRetrievalLinks(@Param("dsId") String dsId,
                                         @Param("operationKeyword") String operationKeyword,
                                         @Param("serviceClass") String serviceClass,
                                         @Param("tableName") String tableName,
                                         @Param("topK") int topK);
}
