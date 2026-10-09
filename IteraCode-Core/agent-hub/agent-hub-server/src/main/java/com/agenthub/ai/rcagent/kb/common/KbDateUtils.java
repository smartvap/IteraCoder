package com.agenthub.ai.rcagent.kb.common;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * kb 模块时间解析工具：接口入参以 yyyy-MM-dd HH:mm:ss 字符串传递时间范围。
 */
public final class KbDateUtils {

    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    private KbDateUtils() {
    }

    /** 解析 yyyy-MM-dd HH:mm:ss；空/非法返回 null（由调用方决定是否 40000） */
    public static Date parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new SimpleDateFormat(PATTERN).parse(text.trim());
        } catch (ParseException e) {
            return null;
        }
    }
}
