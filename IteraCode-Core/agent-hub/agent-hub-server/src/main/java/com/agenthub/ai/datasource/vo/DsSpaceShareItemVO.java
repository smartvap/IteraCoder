package com.agenthub.ai.datasource.vo;

import lombok.Data;

/**
 * 知识空间共享条目（api-contract §4.3 DsSpaceShareItemVO）。
 *
 * <p>仅承载共享数据源的最小可展示字段。安全约束（AGENTS.md 5.x）：**禁止**扩展任何连接或凭据字段
 * （如 {@code jdbcUrl} / {@code readonlyUser} / {@code readonlyPasswordRef} / {@code whitelistJson} /
 * {@code limitsJson}），避免共享清单成为敏感信息泄露通道。</p>
 *
 * <p>说明：存量查询 {@code selectBoundSpaces} 仅返回 dsId/spaceName/manageState，
 * 故 dsName/systemCode 为可选字段（当前可能为空），保留以对齐 api-contract 契约、便于后续扩展。</p>
 */
@Data
public class DsSpaceShareItemVO {

    /** 数据源标识（必填） */
    private String dsId;

    /** 数据源显示名（可选） */
    private String dsName;

    /** 管理态：DRAFT/ACTIVE/DISABLED（DELETED 不参与共享统计） */
    private String manageState;

    /** 归属系统编码（可选，用于前端提示跨系统共享） */
    private String systemCode;
}
