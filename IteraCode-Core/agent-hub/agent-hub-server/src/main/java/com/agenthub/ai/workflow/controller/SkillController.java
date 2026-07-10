package com.agenthub.ai.workflow.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.workflow.skill.SkillContent;
import com.agenthub.ai.workflow.skill.SkillLoader;
import com.agenthub.ai.workflow.skill.SkillMetadata;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理控制器：查询已加载的 Agent 技能
 */
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/skills")
@RequiredArgsConstructor
@Tag(name = "技能管理", description = "查询和管理 AI Agent 技能")
public class SkillController {

    private final SkillLoader skillLoader;

    @GetMapping
    @Operation(summary = "获取所有技能列表")
    public BaseResponse<List<Map<String, Object>>> listSkills() {
        List<Map<String, Object>> result = skillLoader.getAllSkills().values().stream()
                .map(this::toSummary)
                .toList();
        return ResultUtils.success(result);
    }

    @GetMapping("/{skillName}")
    @Operation(summary = "获取指定技能的完整内容")
    public BaseResponse<Map<String, Object>> getSkill(@PathVariable String skillName) {
        SkillContent skill = skillLoader.getSkill(skillName);
        if (skill == null) {
            return ResultUtils.error(404, "技能不存在: " + skillName);
        }
        Map<String, Object> result = toDetail(skill);
        return ResultUtils.success(result);
    }

    @GetMapping("/category/{category}")
    @Operation(summary = "按类别（dev/ops）获取技能列表")
    public BaseResponse<List<Map<String, Object>>> listByCategory(@PathVariable String category) {
        List<Map<String, Object>> result = skillLoader.getSkillsByCategory(category).stream()
                .map(this::toSummary)
                .toList();
        return ResultUtils.success(result);
    }

    private Map<String, Object> toSummary(SkillContent skill) {
        SkillMetadata meta = skill.getMetadata();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", meta.getName());
        map.put("displayName", meta.getDisplayName());
        map.put("description", meta.getDescription());
        map.put("directory", meta.getDirectory());
        map.put("model", meta.getModel());
        map.put("order", meta.getOrder());
        return map;
    }

    private Map<String, Object> toDetail(SkillContent skill) {
        SkillMetadata meta = skill.getMetadata();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", meta.getName());
        map.put("displayName", meta.getDisplayName());
        map.put("description", meta.getDescription());
        map.put("directory", meta.getDirectory());
        map.put("model", meta.getModel());
        map.put("temperature", meta.getTemperature());
        map.put("outputKey", meta.getOutputKey());
        map.put("order", meta.getOrder());
        map.put("maxIterations", meta.getMaxIterations());
        map.put("maxRounds", meta.getMaxRounds());
        map.put("tools", meta.getTools());
        map.put("instruction", skill.getInstruction());
        return map;
    }
}