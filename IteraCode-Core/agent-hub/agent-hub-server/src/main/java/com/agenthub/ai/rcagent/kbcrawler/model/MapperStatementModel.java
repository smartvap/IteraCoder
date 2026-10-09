package com.agenthub.ai.rcagent.kbcrawler.model;

import lombok.Data;

/**
 * Mapper statement 模型（select/insert/update/delete + 目标表 + 动态标签骨架）。
 */
@Data
public class MapperStatementModel {

    /** statement id（如 selectByBalance） */
    private String id;

    /** 语句类型：select/insert/update/delete */
    private String statementType;

    /** 目标表（启发识别，可空） */
    private String targetTable;

    /** 涉及表 */
    private java.util.List<String> relatedTables;

    /** SQL 文本（静态骨架） */
    private String sqlText;

    /** 动态标签骨架（if/foreach/where/set 等） */
    private String dynamicSkeleton;

    /** 是否含未解析动态片段（foreach 等，待运行时补齐） */
    private boolean dynamicUnresolved;
}
