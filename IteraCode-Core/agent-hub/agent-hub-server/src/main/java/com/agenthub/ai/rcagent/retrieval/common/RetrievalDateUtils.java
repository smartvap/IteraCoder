package com.agenthub.ai.rcagent.retrieval.common;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * retrieval 模块时间解析工具：接口入参以 yyyy-MM-dd HH:mm:ss 字符串传递时间范围；
 * 支持纯日期 yyyy-MM-dd（当日 00:00:00）。
 */
public final class RetrievalDateUtils {

    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    private RetrievalDateUtils() {
    }

    /** 解析 yyyy-MM-dd HH:mm:ss；空/非法返回 null（由调用方决定是否 40000） */
    public static Date parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            return new SimpleDateFormat(PATTERN).parse(t);
        } catch (ParseException ignored) {
            // 继续尝试纯日期
        }
        try {
            return new SimpleDateFormat(DATE_PATTERN).parse(t);
        } catch (ParseException ignored) {
            return null;
        }
    }
}
