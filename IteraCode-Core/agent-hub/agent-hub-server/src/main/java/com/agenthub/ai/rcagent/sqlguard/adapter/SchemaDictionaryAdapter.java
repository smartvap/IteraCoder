package com.agenthub.ai.rcagent.sqlguard.adapter;

import com.agenthub.ai.rcagent.sqlguard.config.SqlGuardProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema 字典 HTTP 适配器（module-004 kb-storage-review 检索契约）。
 *
 * <p>复用 LlmWikiClient 式 JDK HttpClient(HTTP/1.1) + fastjson2：
 * 走 kb 检索端点 {@code POST /api/v1/kb/retrieval/schema} 与 {@code /api/v1/kb/retrieval/relation}；
 * kb 不可用时返回 available=false，由上层降级 db-access-common MetadataProvider 运行时元数据
 * （spec A.4：字典缺失自动降级不阻塞主链路）。</p>
 */
@Slf4j
@Component
public class SchemaDictionaryAdapter {

    private final SqlGuardProperties properties;
    private final HttpClient httpClient;

    public SchemaDictionaryAdapter(SqlGuardProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getKbTimeoutMs()))
                .build();
    }

    /**
     * 检索 kb Schema 字典：入参 dsId + 目标表名列表（旧接口，保持向后兼容）。
     *
     * @return available=false 表示 kb 不可用/未收录，调用方应降级 MetadataProvider
     */
    public SchemaDictSnapshot lookupSchema(String dsId, List<String> tableNames) {
        return lookupSchema(dsId, tableNames, null);
    }

    /**
     * 检索 kb Schema 字典：入参 dsId + 纯表名列表 + 限定名列表（schema.table）。
     *
     * <p>限定名用于跨库同名表的精确匹配（kb 端按 UPPER(schema_name.table_name) 过滤）；
     * 未传限定名时退化为纯表名过滤，保持旧行为。若两者皆空则直接返回不可用。</p>
     *
     * @return available=false 表示 kb 不可用/未收录，调用方应降级 MetadataProvider
     */
    public SchemaDictSnapshot lookupSchema(String dsId, List<String> tableNames,
                                           List<String> qualifiedTableNames) {
        SchemaDictSnapshot snapshot = newEmptySnapshot(dsId);
        boolean noTable = tableNames == null || tableNames.isEmpty();
        boolean noQualified = qualifiedTableNames == null || qualifiedTableNames.isEmpty();
        if (noTable && noQualified) {
            return snapshot;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("ds_id", dsId);
            if (!noTable) {
                body.put("table_names", tableNames);
            }
            if (!noQualified) {
                body.put("qualified_table_names", qualifiedTableNames);
            }
            body.put("top_k", 50);
            String resp = postJson("/kb/retrieval/schema", body);
            return parseSchemaPayload(dsId, resp);
        } catch (Exception ex) {
            log.warn("sqlguard: kb schema 字典不可用(dsId={})，降级运行时元数据: {}", dsId, safe(ex.getMessage()));
            return snapshot;
        }
    }

    /**
     * 解析 kb Schema 检索响应（抽取为包级可见方法便于单测 schema 回填）。
     *
     * <p>tables/columns 命中项若携带 schemaName 则回填 schema，
     * hitQualifiedTables 记录 schema.table（无 schema 退化为纯表名）。</p>
     */
    SchemaDictSnapshot parseSchemaPayload(String dsId, String resp) {
        SchemaDictSnapshot snapshot = newEmptySnapshot(dsId);
        JSONObject root = JSON.parseObject(resp);
        JSONObject data = root == null ? null : root.getJSONObject("data");
        if (data == null) {
            return snapshot;
        }
        snapshot.setAvailable(true);
        snapshot.setSource("KB_DICTIONARY");
        JSONArray tables = data.getJSONArray("tables");
        if (tables != null) {
            for (int i = 0; i < tables.size(); i++) {
                JSONObject t = tables.getJSONObject(i);
                String name = t.getString("tableName");
                if (name != null) {
                    snapshot.getHitTables().add(name);
                    snapshot.getHitQualifiedTables().add(qualify(t.getString("schemaName"), name));
                }
            }
        }
        JSONArray columns = data.getJSONArray("columns");
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) {
                JSONObject c = columns.getJSONObject(i);
                SchemaColumnHit col = new SchemaColumnHit();
                col.setSchema(c.getString("schemaName"));
                col.setTable(c.getString("tableName"));
                col.setColumn(c.getString("columnName"));
                col.setDataType(c.getString("dataType"));
                col.setPk(intToBool(c.getInteger("isPk")));
                col.setFk(intToBool(c.getInteger("isFk")));
                col.setSource("KB_DICTIONARY");
                if (col.getColumn() != null) {
                    snapshot.getColumns().add(col);
                }
            }
        }
        return snapshot;
    }

    /**
     * 检索 kb 表关系字典（JOIN 证据）。
     */
    public SchemaDictSnapshot lookupRelation(String dsId, String tableA, String tableB) {
        SchemaDictSnapshot snapshot = newEmptySnapshot(dsId);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("ds_id", dsId);
            if (tableA != null) {
                body.put("table_a", tableA);
            }
            if (tableB != null) {
                body.put("table_b", tableB);
            }
            String resp = postJson("/kb/retrieval/relation", body);
            JSONObject root = JSON.parseObject(resp);
            JSONObject data = root == null ? null : root.getJSONObject("data");
            if (data == null) {
                return snapshot;
            }
            snapshot.setAvailable(true);
            snapshot.setSource("KB_DICTIONARY");
            JSONArray rels = data.getJSONArray("relations");
            if (rels != null) {
                for (int i = 0; i < rels.size(); i++) {
                    JSONObject r = rels.getJSONObject(i);
                    RelationEvidence ev = new RelationEvidence();
                    ev.setTableA(r.getString("tableA"));
                    ev.setColumnA(r.getString("columnA"));
                    ev.setTableB(r.getString("tableB"));
                    ev.setColumnB(r.getString("columnB"));
                    ev.setRelationType(r.getString("relationType"));
                    ev.setConfidence(r.getDouble("confidence"));
                    ev.setVerified(intToBool(r.getInteger("verified")));
                    snapshot.getRelations().add(ev);
                }
            }
            return snapshot;
        } catch (Exception ex) {
            log.warn("sqlguard: kb relation 字典不可用(dsId={})，降级运行时元数据: {}", dsId, safe(ex.getMessage()));
            snapshot.setAvailable(false);
            snapshot.setSource("UNAVAILABLE");
            return snapshot;
        }
    }

    /**
     * 通用 POST JSON（统一 HTTP/1.1，超时收敛，返回体按 UTF-8 读）。
     */
    private String postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
        String base = properties.getKbBaseUrl() == null ? "" : properties.getKbBaseUrl().replaceAll("/+$", "");
        String json = JSON.toJSONString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofMillis(properties.getKbTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return resp.body();
        }
        throw new IOException("kb retrieval http " + resp.statusCode());
    }

    private Boolean intToBool(Integer v) {
        if (v == null) {
            return null;
        }
        return Integer.valueOf(1).equals(v);
    }

    /** 空快照模板：不可用 + 空集合（避免调用方 NPE）。 */
    private SchemaDictSnapshot newEmptySnapshot(String dsId) {
        SchemaDictSnapshot snapshot = new SchemaDictSnapshot();
        snapshot.setDsId(dsId);
        snapshot.setHitTables(new ArrayList<>());
        snapshot.setHitQualifiedTables(new ArrayList<>());
        snapshot.setColumns(new ArrayList<>());
        snapshot.setRelations(new ArrayList<>());
        snapshot.setAvailable(false);
        snapshot.setSource("UNAVAILABLE");
        return snapshot;
    }

    /** 限定名：schema 非空返回 schema.name，否则纯表名（与 TableRef/ColumnRef 口径一致）。 */
    private String qualify(String schema, String name) {
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    private String safe(String msg) {
        if (msg == null || msg.isBlank()) {
            return "无细节";
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
