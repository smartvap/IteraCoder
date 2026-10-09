package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 归因任务状态机（data-model §5 AttributionTaskStatus）。
 *
 * <p>存储值=枚举 name（大写），与 rcagent_attribution_task.status 列对齐。</p>
 */
public enum AttributionTaskStatus {

    /** 异常识别中（FR-001） */
    DETECTING,
    /** 输出"无异常/口径问题"说明阶段 */
    EXPLAINING,
    /** 维度下钻/贡献拆解中（FR-002/003） */
    DRILLING,
    /** 归因查询只读执行中（FR-004） */
    EXECUTING,
    /** 结论生成/证据组装中（FR-006） */
    CONCLUDING,
    /** 归因 SQL 人工审核中（MED/HIGH，FR-004/007） */
    QUERY_REVIEWING,
    /** 归因结论人工审核中（FR-007） */
    CONCLUSION_REVIEWING,
    /** 完成（平稳/真异常结论/数据问题/降级展示） */
    FINISHED,
    /** 失败（错误不泄露 SQL/凭据） */
    FAILED,
    /** 取消/回退终止 */
    CANCELLED;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (AttributionTaskStatus s : values()) {
            if (s.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
