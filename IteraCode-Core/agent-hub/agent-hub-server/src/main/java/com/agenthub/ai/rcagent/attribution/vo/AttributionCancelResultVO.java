package com.agenthub.ai.rcagent.attribution.vo;

import com.agenthub.ai.rcagent.attribution.constant.AttributionTaskStatus;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionTask;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 取消/回退归因任务响应视图（api-contract 接口 7 data 结构）。
 *
 * <p>契约要求响应 data 携带更新后任务对象（data.task = AttributionTaskVO）：取消场景
 * task.status=CANCELLED；回退场景 task.status=DRILLING 且 task.drill_depth 已回落至指定层。
 * 使用强类型 VO 替代弱类型 Map（rules.md：新代码禁止弱类型结构输出）。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionCancelResultVO {

    /** 更新后任务视图（契约 data.task，task_id/status/drill_depth 等字段 snake_case 输出） */
    private AttributionTaskVO task;

    /** 执行动作说明：CANCELLED=取消任务；RESET_TO_LAYER_{n}=回退至第 n 层后继续下钻 */
    private String action;

    /**
     * 由更新后的任务实体组装结果视图（action 依据落库后的任务状态推导，保证与实际一致）。
     *
     * @param task 更新后任务（cancelOrReset 已落库返回）
     */
    public static AttributionCancelResultVO of(RcagentAttributionTask task) {
        AttributionCancelResultVO vo = new AttributionCancelResultVO();
        vo.setTask(AttributionTaskVO.from(task));
        if (task != null && AttributionTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            vo.setAction("CANCELLED");
        } else {
            int depth = task == null || task.getDrillDepth() == null ? 0 : task.getDrillDepth();
            vo.setAction("RESET_TO_LAYER_" + depth);
        }
        return vo;
    }
}
