package com.agenthub.ai.rcagent.kb.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 结构变更摘要项（Schema batch changes / table save changes 元素）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KbChangeSummaryVO {

    /** 表名 */
    private String tableName;

    /** 新增列 */
    private List<String> addedColumns;

    /** 删除列 */
    private List<String> droppedColumns;

    /** 类型/主外键变化列 */
    private List<String> changedColumns;

    /** 本次新增的索引名（可空，与 addedColumns 同构） */
    private List<String> addedIndexes;

    /** 本次被软删（缺失）的索引名（可空，与 droppedColumns 同构） */
    private List<String> droppedIndexes;

    /** 引用影响对象摘要 */
    private List<KbImpactItemVO> impacts;
}
