package com.agenthub.ai.rcagent.kb.entity;

import java.math.BigDecimal;

/**
 * kb 知识对象公共行接口。
 *
 * <p>7 张知识对象表（kb_table/kb_column/kb_business_concept/kb_metric/kb_sql_template/kb_biz_link/kb_relation）
 * 共享「可信度四元组 + 状态机 + 版本计数」字段，由实体统一实现该接口，
 * 便于审核/置信度/软删除等横切逻辑以公共视图定位并更新目标行，
 * 避免使用 Map/JSONObject 弱类型透传。</p>
 */
public interface KbKnowledgeRow {

    Long getId();

    void setId(Long id);

    String getDsId();

    void setDsId(String dsId);

    String getKnowledgeSource();

    void setKnowledgeSource(String knowledgeSource);

    BigDecimal getConfidence();

    void setConfidence(BigDecimal confidence);

    Integer getVerified();

    void setVerified(Integer verified);

    String getEvidence();

    void setEvidence(String evidence);

    String getStatus();

    void setStatus(String status);

    Integer getVersion();

    void setVersion(Integer version);

    Integer getHitCount();

    void setHitCount(Integer hitCount);

    Integer getErrorCount();

    void setErrorCount(Integer errorCount);
}
