package com.agenthub.ai.llmwiki.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.llmwiki.client.LlmWikiClient;
import com.agenthub.ai.llmwiki.client.LlmWikiClientException;
import com.agenthub.ai.llmwiki.dto.HealthVO;
import com.agenthub.ai.llmwiki.dto.PageVO;
import com.agenthub.ai.llmwiki.dto.PageWriteRequest;
import com.agenthub.ai.llmwiki.dto.SearchHitVO;
import com.agenthub.ai.llmwiki.dto.SearchRequest;
import com.agenthub.ai.llmwiki.dto.SpaceCreateRequest;
import com.agenthub.ai.llmwiki.dto.SpaceVO;
import com.agenthub.ai.llmwiki.dto.SyncTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * llm-wiki 知识库管理 API（代理到 llm-wiki 服务）
 * <p>
 * 访问路径：/api/v1/llmwiki/**；页面 path 支持中文与子目录（{*path} 捕获）。
 */
@Tag(name = "LlmWikiController", description = "llm-wiki 知识库管理（知识空间/知识页/检索/同步）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/llmwiki")
public class LlmWikiController {

    private final LlmWikiClient client;

    public LlmWikiController(LlmWikiClient client) {
        this.client = client;
    }

    @ExceptionHandler(LlmWikiClientException.class)
    public BaseResponse<String> handleLlmWikiError(LlmWikiClientException e) {
        int code = e.getStatusCode() > 0 ? e.getStatusCode() : 500;
        log.warn("llm-wiki 代理错误: code={}, msg={}", code, e.getMessage());
        return ResultUtils.error(code, e.getMessage());
    }

    // ==================== 健康 ====================

    @Operation(summary = "llm-wiki 健康状态")
    @GetMapping("/health")
    public BaseResponse<HealthVO> health() {
        return ResultUtils.success(client.health());
    }

    // ==================== 空间 ====================

    @Operation(summary = "空间列表")
    @GetMapping("/spaces")
    public BaseResponse<List<SpaceVO>> listSpaces() {
        return ResultUtils.success(client.listSpaces());
    }

    @Operation(summary = "创建空间")
    @PostMapping("/spaces")
    public BaseResponse<SpaceVO> createSpace(@RequestBody SpaceCreateRequest req) {
        return ResultUtils.success(client.createSpace(req));
    }

    @Operation(summary = "删除空间")
    @DeleteMapping("/spaces/{name}")
    public BaseResponse<Void> deleteSpace(@PathVariable String name) {
        client.deleteSpace(name);
        return ResultUtils.success(null);
    }

    // ==================== 页面 ====================

    @Operation(summary = "页面列表（可按 type 过滤）")
    @GetMapping("/spaces/{space}/pages")
    public BaseResponse<List<PageVO>> listPages(@PathVariable String space,
                                                @RequestParam(required = false) String type) {
        return ResultUtils.success(client.listPages(space, type));
    }

    @Operation(summary = "页面详情")
    @GetMapping("/spaces/{space}/pages/{*path}")
    public BaseResponse<PageVO> getPage(@PathVariable String space, @PathVariable String path) {
        return ResultUtils.success(client.getPage(space, path));
    }

    @Operation(summary = "新建页面（path 在 body 中指定）")
    @PostMapping("/spaces/{space}/pages")
    public BaseResponse<PageVO> createPage(@PathVariable String space,
                                           @RequestBody PageWriteRequest req) {
        return ResultUtils.success(client.createPage(space, req));
    }

    @Operation(summary = "更新页面（以 URL path 为准）")
    @PutMapping("/spaces/{space}/pages/{*path}")
    public BaseResponse<PageVO> updatePage(@PathVariable String space, @PathVariable String path,
                                           @RequestBody PageWriteRequest req) {
        return ResultUtils.success(client.updatePage(space, path, req));
    }

    @Operation(summary = "删除页面")
    @DeleteMapping("/spaces/{space}/pages/{*path}")
    public BaseResponse<Void> deletePage(@PathVariable String space, @PathVariable String path) {
        client.deletePage(space, path);
        return ResultUtils.success(null);
    }

    @Operation(summary = "发起增量同步（异步任务，结果需轮询 /tasks/{taskId}）")
    @PostMapping("/spaces/{space}/pages/sync")
    public BaseResponse<SyncTaskVO> syncPages(@PathVariable String space) {
        return ResultUtils.success(client.syncPages(space));
    }

    @Operation(summary = "上传文档（md/txt/docx/pdf/pptx/xlsx），发起异步任务归一化为知识页")
    @PostMapping(value = "/spaces/{space}/pages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<SyncTaskVO> uploadPage(@PathVariable String space,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
                                               @RequestParam(value = "subdir", required = false, defaultValue = "") String subdir) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("上传文件名无效");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage(), e);
        }
        SyncTaskVO task = client.uploadPage(space, fileName, content, subdir, overwrite);
        return ResultUtils.success(task);
    }

    @Operation(summary = "查询 llm-wiki 异步任务状态（sync/upload）")
    @GetMapping("/tasks/{taskId}")
    public BaseResponse<SyncTaskVO> getSyncTask(@PathVariable String taskId) {
        return ResultUtils.success(client.getSyncTask(taskId));
    }

    // ==================== 检索 ====================

    @Operation(summary = "混合检索（BM25 + 向量）")
    @PostMapping("/spaces/{space}/search")
    public BaseResponse<List<SearchHitVO>> search(@PathVariable String space,
                                                  @RequestBody SearchRequest req) {
        return ResultUtils.success(client.search(space, req));
    }
}
