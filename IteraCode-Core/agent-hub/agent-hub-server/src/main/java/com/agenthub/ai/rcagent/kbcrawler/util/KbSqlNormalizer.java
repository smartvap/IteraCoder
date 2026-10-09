package com.agenthub.ai.rcagent.kbcrawler.util;

import com.agenthub.ai.rcagent.kbcrawler.model.NormalizedSqlModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 归一化工具（FR-004）：参数占位/大小写空格规范化/敏感字面量剔除 + 指纹计算 + 涉及表提取。
 *
 * <p>安全约束（BR-001/AGENTS.md 5.x）：归一化必须剔除字符串/数值字面量与敏感原文，
 * 指纹仅基于归一化文本；调用方/日志不得输出参数明细。识别到疑似明文敏感参数时置 rejected 供 fail-closed。</p>
 */
@Component
public class KbSqlNormalizer {

    /** 常见敏感列名（用于 fail-closed 判定：SQL 中出现敏感列 = '值' 字样视为明文参数） */
    private static final Pattern SENSITIVE_COLUMN_LITERAL = Pattern.compile(
            "(?i)\\b(password|pwd|secret|token|api[_-]?key|access[_-]?key|credential|pay_pwd|id_number|phone)\\b\\s*=\\s*'[^']+'");

    /** 字符串字面量（含 '' 转义） */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");

    /** 数字字面量 */
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    /** 表名提取：FROM/JOIN/INTO/UPDATE/TABLE 后紧邻标识符 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|into|update)\\s+([`\"\\[]?[A-Za-z0-9_$.]+[`\"\\]]?)");

    /** 多空白 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** SHA-256 指纹长度 64 hex */
    private static final int FINGERPRINT_LEN = 64;

    /**
     * 归一化 + 指纹 + 涉及表。
     *
     * @param rawSql 原始 SQL
     * @return 归一化模型（rejected=true 时后续不应写入）
     */
    public NormalizedSqlModel normalize(String dsId, String dbType, String rawSql) {
        NormalizedSqlModel model = new NormalizedSqlModel();
        model.setDsId(dsId);
        model.setDbType(dbType);
        model.setRawSql(rawSql);

        if (rawSql == null || rawSql.isBlank()) {
            model.setRejected(true);
            model.setRejectReason("SQL 为空");
            return model;
        }
        // fail-closed：疑似明文敏感参数（敏感列 = '值'）直接拒收
        if (SENSITIVE_COLUMN_LITERAL.matcher(rawSql).find()) {
            model.setRejected(true);
            model.setRejectReason("含明文敏感参数");
            return model;
        }

        // 归一化：先去注释（-- 到行尾、/* */ 简易）、字符串→?、数字→?、空白折叠、大小写统一
        String sql = stripComments(rawSql);
        sql = STRING_LITERAL.matcher(sql).replaceAll("?");
        sql = NUMBER_LITERAL.matcher(sql).replaceAll("?");
        sql = sql.replaceAll("(?i)\\bnull\\b", "?");
        sql = WHITESPACE.matcher(sql.trim()).replaceAll(" ");
        sql = sql.toUpperCase(Locale.ROOT);
        // 去掉尾部悬挂分号
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        model.setNormalizedSql(sql);
        model.setSqlFingerprint(fingerprint(sql));
        model.setInvolvedTables(extractTables(sql));
        return model;
    }

    /** 去除 SQL 行注释/块注释（仅服务端内存处理，避免字面量注释影响归一化） */
    private String stripComments(String sql) {
        // 简易处理：剔除 -- 至行尾 与 /* */ 块；字符串内注释不处理（字符串先整体占位亦可接受）
        String s = sql.replaceAll("(?m)--.*$", " ");
        s = s.replaceAll("/\\*.*?\\*/", " ");
        return s;
    }

    /** 归一化 SQL 指纹（SHA-256 hex） */
    public String fingerprint(String normalizedSql) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(normalizedSql.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(FINGERPRINT_LEN);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    /** 提取涉及表（简单 FROM/JOIN，去重保序；剔除函数/别名后取首标识符段） */
    public List<String> extractTables(String normalizedSql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher m = TABLE_REF.matcher(normalizedSql);
        while (m.find()) {
            String ref = cleanRef(m.group(1));
            if (ref != null && !ref.isBlank() && !"DUAL".equalsIgnoreCase(ref)) {
                tables.add(ref);
            }
        }
        return new ArrayList<>(tables);
    }

    /** 清洗表引用：去除 schema/别名片段中非表名部分（简单保留全名+去引号） */
    private String cleanRef(String ref) {
        String r = ref.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        if (r.isEmpty()) {
            return null;
        }
        // 含 schema 时（如 SCHEMA.TABLE）保留完整引用
        return r;
    }
}
