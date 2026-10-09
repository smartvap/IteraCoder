package com.agenthub.ai.rcagent.kb.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * kb 模块 JSON 文本列工具。
 *
 * <p>kb 表的复合列（semantic_tags / sample_stats / related_tables 等）以 TEXT 存 JSON
 * （对齐 fastjson2 风格，data-model 第 8 条）。本工具只负责 String↔JSON 的序列化/解析，
 * 不承载任何业务模型，避免在 Service 中散落 JSON 处理。</p>
 */
public final class KbJsonUtils {

    private KbJsonUtils() {
    }

    /** List → JSON 数组文本；null 返回 null */
    public static String toJson(List<?> list) {
        return list == null ? null : JSON.toJSONString(list);
    }

    /** 对象 → JSON 文本；null 返回 null */
    public static String toJson(Object obj) {
        return obj == null ? null : JSON.toJSONString(obj);
    }

    /** JSON 数组文本 → List<String>；null/空返回空列表 */
    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        JSONArray array = JSON.parseArray(json);
        return array == null ? Collections.emptyList() : array.toJavaList(String.class);
    }

    /** JSON 数组文本 → List<Long>；null/空返回空列表 */
    public static List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        JSONArray array = JSON.parseArray(json);
        return array == null ? Collections.emptyList() : array.toJavaList(Long.class);
    }

    /** 解析 JSON 对象文本；非法/空返回 null（调用方自行处理 40000） */
    public static JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JSON.parseObject(json);
    }
}
