package com.agenthub.ai.workflow.skill;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Skill 完整内容：元数据 + 指令正文
 */
@Data
@AllArgsConstructor
public class SkillContent {
    private SkillMetadata metadata;
    private String instruction;
}