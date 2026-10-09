package com.agenthub.ai.rcagent.kb.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 知识保存/软删除通用响应 VO（接口1-9）。
 * <p>不同保存接口按契约返回部分字段，未用字段为 null 时不下发。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KbSaveResultVO {

    /** 对象 id */
    private Long objectId;

    /** 当前版本号 */
    private Integer version;

    /** 保存后状态 */
    private String status;

    /** 保存后置信度 */
    private BigDecimal confidence;

    /** 是否发生结构变更（需联动） */
    private Boolean structureChanged;

    /** 结构变更影响对象摘要（引用模板/链路，可为空） */
    private List<KbImpactItemVO> impacted;

    /** 指纹（模板保存返回） */
    private String fingerprint;

    /** 更新后频次（模板保存返回） */
    private Integer freq;

    /** 自动创建审核任务 id（LLM 来源知识保存返回） */
    private Long reviewTaskId;
}
