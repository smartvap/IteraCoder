package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.logger.ConversationLogger;
import com.agenthub.ai.base.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 项目管理控制器 — 代码生成后的存储、下载、部署
 * @since 2026-07-29 — 代码生成后的存储、下载、部署
 */
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /** 保存生成的 ZIP 项目到服务端 */
    @PostMapping("/save")
    public BaseResponse<Map<String, Object>> saveProject(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectName") String projectName) {
        String path = projectService.saveProject(file, projectName);
        ConversationLogger.codeGeneration(projectName, -1);
        return ResultUtils.success(Map.of("path", path, "projectName", projectName));
    }

    /** 尝试 Docker 部署 */
    @PostMapping("/deploy/{projectName}")
    public BaseResponse<Map<String, Object>> deploy(@PathVariable String projectName) {
        Map<String, Object> result = projectService.deploy(projectName);
        return ResultUtils.success(result);
    }

    /** 保存日志到项目 logs 目录 */
    @PostMapping("/log/save")
    public BaseResponse<Map<String, Object>> saveLog(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResultUtils.error("日志内容不能为空");
        }
        String path = projectService.saveLog(content);
        return ResultUtils.success(Map.of("path", path));
    }
    public BaseResponse<Map<String, Object>> dockerCheck() {
        boolean available = projectService.isDockerAvailable();
        return ResultUtils.success(Map.of("dockerAvailable", available));
    }
}
