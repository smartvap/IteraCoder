package com.agenthub.ai.dbaccess.model;

/**
 * 数据源状态枚举（data-model.md DsStatus：1=启用 0=停用，停用数据源不可被 resolve）。
 */
public enum DsStatus {

    /** 停用 */
    DISABLED(0, "停用"),

    /** 启用 */
    ENABLED(1, "启用");

    private final int code;
    private final String desc;

    DsStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按状态码解析；未知返回 {@code null}。
     */
    public static DsStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (DsStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
