package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 下钻节点类型（data-model §5 NodeType，rcagent_attribution_node.node_type）。
 */
public enum NodeType {

    /** 首层根节点（总体/异常识别结果） */
    ROOT,
    /** 单维度分组下钻 */
    GROUP_DRILL,
    /** 多维交叉下钻（营业厅 × 时段等） */
    CROSS_DRILL,
    /** 口径/数据质量排查节点 */
    QUALITY_CHECK;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (NodeType t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
