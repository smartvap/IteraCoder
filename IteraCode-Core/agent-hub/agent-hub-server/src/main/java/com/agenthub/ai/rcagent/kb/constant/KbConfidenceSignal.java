package com.agenthub.ai.rcagent.kb.constant;

/**
 * 置信度信号类型（FR-006，统一收敛于 KbConfidenceService）。
 */
public enum KbConfidenceSignal {

    /** 正面信号：命中且正确（慢升） */
    POSITIVE,
    /** 负面信号：纠错（快降） */
    NEGATIVE,
    /** 人工确认 */
    CONFIRM,
    /** 结构变更影响 */
    STRUCTURE_CHANGE
}
