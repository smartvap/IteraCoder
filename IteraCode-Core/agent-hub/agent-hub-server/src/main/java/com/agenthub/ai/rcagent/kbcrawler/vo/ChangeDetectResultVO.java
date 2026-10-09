package com.agenthub.ai.rcagent.kbcrawler.vo;

import com.agenthub.ai.rcagent.kbcrawler.model.ChangeDetectDiff;
import lombok.Data;

import java.util.List;

/**
 * 接口7 发版触发变更检测响应。
 */
@Data
public class ChangeDetectResultVO {

    /** 变更条目（见 ChangeType 数据字典） */
    private List<ChangeDetectDiff> diffs;

    /** 结构变更影响定位到的模板/链路数（needs_review） */
    private Long impactedObjects;
}
