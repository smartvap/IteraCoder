package com.agenthub.ai.rcagent.feedback.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * feedback 模块时间解析工具：接口入参时间范围以 yyyy-MM-dd HH:mm:ss 字符串传递。
 */
public final class FeedbackDateUtil {

    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    private FeedbackDateUtil() {
    }

    /** 解析 yyyy-MM-dd HH:mm:ss；空/非法返回 null */
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
