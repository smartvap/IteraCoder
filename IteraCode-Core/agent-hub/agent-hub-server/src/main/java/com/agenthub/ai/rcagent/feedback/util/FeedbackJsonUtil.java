package com.agenthub.ai.rcagent.feedback.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * feedback 模块 JSON 文本列工具（fb_feedback_flow JSON 列 + 建议/信号 JSON）。
 *
 * <p>仅负责 String↔JSON 序列化/解析，不承载业务模型，避免 Service 散落 JSON 处理。</p>
 */
public final class FeedbackJsonUtil {

    private FeedbackJsonUtil() {
    }

    /** 对象 → JSON 文本；null 返回 null */
    public static String toJson(Object obj) {
        return obj == null ? null : JSON.toJSONString(obj);
    }

    /** 解析 JSON 对象文本；非法/空返回 null */
    public static JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 数组文本 → List<Long>；null/空返回空列表 */
    public static List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        JSONArray array = JSON.parseArray(json);
        return array == null ? Collections.emptyList() : array.toJavaList(Long.class);
    }

    /** JSON 数组文本 → List<KnowledgeSuggestion>；null/空返回空列表 */
    public static <T> List<T> parseList(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        JSONArray array = JSON.parseArray(json);
        return array == null ? Collections.emptyList() : array.toJavaList(clazz);
    }

    /** 简单字符串数组拼接（用于 writeback_repo 聚合，如 A,B） */
    public static String joinRepo(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> distinct = new ArrayList<>();
        for (String item : items) {
            if (item != null && !item.isBlank() && !distinct.contains(item)) {
                distinct.add(item);
            }
        }
        return distinct.isEmpty() ? null : String.join(",", distinct);
    }
}
