package com.agenthub.ai.rcagent.engine.dto;

import lombok.Data;

import java.util.List;

/**
 * 候选源解析校验入参（POST /api/v1/rcagent/sources/resolve，api-contract.md §5.7）。
 *
 * <p>三字段均可空但至少一个非空；优先级：dsIds &gt; systemCode &gt; dsId。
 * 手动 if 校验（项目未启用 @Valid/@Validated），失败统一 40000 中文提示。</p>
 */
@Data
public class RcaSourceResolveRequest {

    /** 单数据源 / 主源（可空，≤64） */
    private String dsId;

    /** 系统编码（可空，≤64）：按系统全量展开候选源 */
    private String systemCode;

    /** 显式候选源子集（可空，每项 ≤64，须同属一个系统）：优先级最高 */
    private List<String> dsIds;
}
