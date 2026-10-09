package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * 五级验证单级结果（PASS/FAIL/WARN/SKIP）。
 */
public enum SqlGuardLevelResult {

    /** 通过 */
    PASS("PASS"),
    /** 失败（阻断进入下一级） */
    FAIL("FAIL"),
    /** 警告（尽力校验提示，不阻断） */
    WARN("WARN"),
    /** 跳过（不适用/未执行） */
    SKIP("SKIP");

    private final String code;

    SqlGuardLevelResult(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static SqlGuardLevelResult parse(String text) {
        if (text == null || text.isBlank()) {
            return SKIP;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SqlGuardLevelResult r : values()) {
            if (r.code.equals(upper) || r.name().equals(upper)) {
                return r;
            }
        }
        return SKIP;
    }
}
