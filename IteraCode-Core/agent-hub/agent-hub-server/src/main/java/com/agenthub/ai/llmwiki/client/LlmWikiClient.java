package com.agenthub.ai.llmwiki.client;

import com.agenthub.ai.llmwiki.config.LlmWikiProperties;
import com.agenthub.ai.llmwiki.dto.HealthVO;
import com.agenthub.ai.llmwiki.dto.PageVO;
import com.agenthub.ai.llmwiki.dto.PageWriteRequest;
import com.agenthub.ai.llmwiki.dto.SearchHitVO;
import com.agenthub.ai.llmwiki.dto.SearchRequest;
import com.agenthub.ai.llmwiki.dto.SpaceCreateRequest;
import com.agenthub.ai.llmwiki.dto.SpaceVO;
import com.agenthub.ai.llmwiki.dto.SyncTaskVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * llm-wiki 知识库服务 HTTP 客户端（JDK HttpClient + Jackson）。
 * <p>
 * 与 GitProjectService 同款调用风格：JDK 内置 HttpClient，零额外依赖。
 * 页面 path 支持中文与子目录（如 concepts/投诉.md），转发前按段 URL 编码。
 */
@Slf4j
@Component
public class LlmWikiClient {

    private final LlmWikiProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmWikiClient(LlmWikiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
        log.info("llm-wiki 客户端就绪: baseUrl={}", props.trimmedBaseUrl());
    }

    // ==================== 空间 ====================

    public HealthVO health() {
        return send("GET", "/health", null, HealthVO.class);
    }

    public List<SpaceVO> listSpaces() {
        return send("GET", "/spaces", null, new TypeReference<List<SpaceVO>>() {}.getType());
    }

    public SpaceVO createSpace(SpaceCreateRequest req) {
        return send("POST", "/spaces", req, SpaceVO.class);
    }

    public void deleteSpace(String name) {
        send("DELETE", "/spaces/" + enc(name), null, null);
    }

    // ==================== 页面 ====================

    public List<PageVO> listPages(String space, String type) {
        String q = (type == null || type.isBlank()) ? "" : "?type=" + enc(type);
        return send("GET", "/spaces/" + enc(space) + "/pages" + q, null,
                new TypeReference<List<PageVO>>() {}.getType());
    }

    public PageVO getPage(String space, String path) {
        return send("GET", "/spaces/" + enc(space) + "/pages/" + encPath(path), null, PageVO.class);
    }

    public PageVO createPage(String space, PageWriteRequest req) {
        return send("POST", "/spaces/" + enc(space) + "/pages", req, PageVO.class);
    }

    public PageVO updatePage(String space, String path, PageWriteRequest req) {
        return send("PUT", "/spaces/" + enc(space) + "/pages/" + encPath(path), req, PageVO.class);
    }

    public void deletePage(String space, String path) {
        send("DELETE", "/spaces/" + enc(space) + "/pages/" + encPath(path), null, null);
    }

    /**
     * 发起异步增量同步（扫描磁盘文件 ↔ 台账 diff），立即返回任务信息。
     * <p>
     * llm-wiki 以 202 秒回任务 JSON（task_id/kind/space/status=running），实际同步结果需
     * 通过 {@link #getSyncTask(String)} 轮询 task_id 获取。
     */
    public SyncTaskVO syncPages(String space) {
        return send("POST", "/spaces/" + enc(space) + "/pages/sync", null, SyncTaskVO.class);
    }

    /**
     * 上传文档到 llm-wiki（multipart/form-data），归一化为知识页，发起后立即返回任务信息。
     * <p>
     * 支持 .md/.markdown/.txt/.docx/.pdf/.pptx/.xlsx；fileName 可含中文/空格，filename 头按 UTF-8 原串发送。
     * llm-wiki 以 202 秒回任务 JSON（task_id/kind=upload/space/status），任务可能处于 error（如
     * 目标页面已存在且 overwrite=false 时 error="页面已存在: xxx"），最终结果需通过
     * {@link #getSyncTask(String)} 轮询 task_id 获取。
     */
    public SyncTaskVO uploadPage(String space, String fileName, byte[] content,
                                 String subdir, boolean overwrite) {
        String boundary = "----LlmWikiBoundary" + System.nanoTime();
        byte[] body = buildUploadBody(boundary, fileName, content, subdir, overwrite);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.trimmedBaseUrl() + "/spaces/" + enc(space) + "/pages/upload"))
                // 上传专用超时：大文档 embedding 可能超过公共 readTimeoutMs(60s)，最多等待 5 分钟
                .timeout(Duration.ofMillis(props.getUploadTimeoutMs()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<byte[]> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            String respBody = new String(resp.body() == null ? new byte[0] : resp.body(),
                    StandardCharsets.UTF_8);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return objectMapper.readValue(respBody, SyncTaskVO.class);
            }
            String detail = extractErrorDetail(respBody);
            log.warn("llm-wiki 上传发起失败: space={}, file={} -> {} {}", space, fileName,
                    resp.statusCode(), detail);
            throw new LlmWikiClientException(resp.statusCode(), respBody,
                    "llm-wiki 返回错误(" + resp.statusCode() + "): " + detail);
        } catch (LlmWikiClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmWikiClientException(-1, null, "llm-wiki 上传被中断: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new LlmWikiClientException(-1, null, "llm-wiki 上传连接失败: " + e.getMessage(), e);
        }
    }

    /** 组装 multipart/form-data 报文：file 文件字段 + overwrite/subdir 文本字段 */
    private static byte[] buildUploadBody(String boundary, String fileName, byte[] content,
                                          String subdir, boolean overwrite) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeFilePart(bos, boundary, "file", fileName, content);
        writeTextPart(bos, boundary, "overwrite", String.valueOf(overwrite));
        writeTextPart(bos, boundary, "subdir", subdir == null ? "" : subdir);
        bos.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return bos.toByteArray();
    }

    private static void writeFilePart(ByteArrayOutputStream bos, String boundary, String name,
                                      String fileName, byte[] content) {
        bos.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.writeBytes(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        bos.writeBytes("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bos.writeBytes(content);
        bos.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTextPart(ByteArrayOutputStream bos, String boundary, String name,
                                      String value) {
        bos.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.writeBytes(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        bos.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        bos.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 异步任务 ====================

    /**
     * 查询 llm-wiki 异步任务状态（sync/upload 发起后轮询）。
     * <p>
     * GET /tasks/{task_id} → 任务 JSON（status: running/completed/error）；任务不存在时 llm-wiki 返回 404。
     */
    public SyncTaskVO getSyncTask(String taskId) {
        return send("GET", "/tasks/" + enc(taskId), null, SyncTaskVO.class);
    }

    // ==================== 检索 ====================

    public List<SearchHitVO> search(String space, SearchRequest req) {
        return send("POST", "/spaces/" + enc(space) + "/search", req,
                new TypeReference<List<SearchHitVO>>() {}.getType());
    }

    // ==================== 通用执行 ====================

    private <T> T send(String method, String path, Object body, Type respType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(props.trimmedBaseUrl() + path))
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            if (body != null) {
                String json = objectMapper.writeValueAsString(body);
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json));
            }
            HttpResponse<byte[]> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                if (respType == null || resp.body() == null || resp.body().length == 0) {
                    return null;
                }
                return objectMapper.readValue(resp.body(),
                        objectMapper.getTypeFactory().constructType(respType));
            }
            String errBody = new String(resp.body() == null ? new byte[0] : resp.body(),
                    StandardCharsets.UTF_8);
            String detail = extractErrorDetail(errBody);
            log.warn("llm-wiki 调用失败: {} {} -> {} {}", method, path, resp.statusCode(), detail);
            throw new LlmWikiClientException(resp.statusCode(), errBody,
                    "llm-wiki 返回错误(" + resp.statusCode() + "): " + detail);
        } catch (LlmWikiClientException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmWikiClientException(-1, null, "llm-wiki 请求被中断: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new LlmWikiClientException(-1, null, "llm-wiki 连接失败: " + e.getMessage(), e);
        }
    }

    /** 从 FastAPI 错误体提取 detail（{detail: "..."}）或原文 */
    private String extractErrorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "无响应内容";
        }
        String trimmed = body.trim();
        try {
            var node = objectMapper.readTree(trimmed);
            var detail = node.get("detail");
            if (detail != null) {
                return detail.isTextual() ? detail.asText() : detail.toString();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，直接截断原文
        }
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    // ==================== URL 编码 ====================

    /** 编码单个路径段（中文/特殊字符），不处理斜杠 */
    private static String enc(String seg) {
        return URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 编码多级页面路径：保留 / 分隔，逐段编码（如 concepts/投诉.md） */
    private static String encPath(String path) {
        String[] segs = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(enc(segs[i]));
        }
        return sb.toString();
    }
}
