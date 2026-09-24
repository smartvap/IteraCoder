package com.agenthub.ai.datasource.constant;

/**
 * 多数据源管理模块业务错误码（43001~43009）。
 *
 * <p>按 api-contract.md 错误码表定义，不改存量 base.common.ErrorCode 枚举；
 * 使用方式：{@code throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在")}。
 * 通用参数错误复用 base ErrorCode.PARAMS_ERROR（40000）。</p>
 */
public final class DsErrorCode {

    private DsErrorCode() {
    }

    /** DS_NOT_FOUND 数据源不存在：按 dsId 查询/操作不存在或已软删 */
    public static final int DS_NOT_FOUND = 43001;

    /**
     * DS_ID_CONFLICT 冲突：**仅** ds_id 已存在（注册重复，禁止覆盖）。
     *
     * <p>语义收窄：不再表示 llm-wiki 同名知识空间冲突（数据源↔知识空间已放宽为 N:1 共享复用）。
     * 常量与数值保留不删，供前端/上游按既有编号识别 ds_id 冲突。</p>
     */
    public static final int DS_ID_CONFLICT = 43002;

    /** INVALID_STATE 状态不允许：对 DELETED 操作/状态机非法迁移/编辑 ds_id/版本冲突 */
    public static final int INVALID_STATE = 43003;

    /** CONNECTION_FAILED 连接失败：message 不含地址凭据 */
    public static final int CONNECTION_FAILED = 43004;

    /** HEALTH_CHECK_FAILED 健康检查未通过：只读/字典/白名单不满足，不可启用（data 带诊断） */
    public static final int HEALTH_CHECK_FAILED = 43005;

    /** SPACE_LINK_UNAVAILABLE 空间联动不可用：llm-wiki 不可达/创建失败（降级保存） */
    public static final int SPACE_LINK_UNAVAILABLE = 43006;

    /** READONLY_POLICY_VIOLATION 只读/白名单缺失：BR-001 不满足禁止 ACTIVE */
    public static final int READONLY_POLICY_VIOLATION = 43007;

    /** UNSUPPORTED_DB_TYPE 不支持的库类型：需先扩展 db-access-common Provider */
    public static final int UNSUPPORTED_DB_TYPE = 43008;

    /** SPACE_NAME_REQUIRED 空间名为空 */
    public static final int SPACE_NAME_REQUIRED = 43009;
}
