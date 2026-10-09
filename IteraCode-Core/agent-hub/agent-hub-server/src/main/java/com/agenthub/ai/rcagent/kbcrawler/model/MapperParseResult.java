package com.agenthub.ai.rcagent.kbcrawler.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Mapper 静态解析产物（kb_crawl_mapper_parse_result，不落库）。
 * 解析单个 mapper XML + 对应 Java DAO 的静态结构；仅骨架，
 * 运行时才能展开的动态片段标记待运行时补齐。
 */
@Data
public class MapperParseResult {

    /** mapper XML 文件路径/标识 */
    private String mapperFile;

    /** namespace（DAO 全限定名） */
    private String namespace;

    /** resultMap 列表：id → (type, column→property 映射) */
    private List<ResultMapModel> resultMaps = new ArrayList<>();

    /** SQL 片段：id → sql 文本 */
    private List<SqlFragmentModel> sqlFragments = new ArrayList<>();

    /** statement 列表 */
    private List<MapperStatementModel> statements = new ArrayList<>();

    /** 需要运行时补齐的动态片段描述（如 foreach 集合参数化） */
    private List<String> dynamicUnresolved = new ArrayList<>();

    /** 单文件解析失败标记（0=成功 1=跳过记录，不阻断整体） */
    private Integer parseFailed = 0;

    /** 解析失败原因（截断） */
    private String parseError;

    @Data
    public static class ResultMapModel {
        /** resultMap id */
        private String id;
        /** 映射类型（POJO 全限定名，可空） */
        private String type;
        /** 列→属性映射 */
        private List<ColumnMapping> mappings = new ArrayList<>();
    }

    @Data
    public static class ColumnMapping {
        /** 列名 */
        private String column;
        /** Java 属性名 */
        private String property;
    }

    @Data
    public static class SqlFragmentModel {
        /** 片段 id */
        private String id;
        /** sql 文本（截断） */
        private String sqlText;
    }
}
