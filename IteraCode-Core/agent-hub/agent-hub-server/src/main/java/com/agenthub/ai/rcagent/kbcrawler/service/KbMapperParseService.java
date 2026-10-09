package com.agenthub.ai.rcagent.kbcrawler.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.kbcrawler.adapter.KbStorageReviewAdapter;
import com.agenthub.ai.rcagent.kbcrawler.config.KbCrawlerProperties;
import com.agenthub.ai.rcagent.kbcrawler.constant.KbCrawlerConstant;
import com.agenthub.ai.rcagent.kbcrawler.model.MapperParseResult;
import com.agenthub.ai.rcagent.kbcrawler.model.MapperStatementModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapper 静态解析服务（FR-003）：解析 mapper XML（resultMap/sql 片段/动态标签骨架）
 * + Java DAO 接口方法链路，抽取"列↔字段↔注释"三元语义补 kb_column 注释、写 kb_biz_link。
 *
 * <p>输入为代码仓库快照目录（配置 agenthub.kbcrawler.mapper.scan-dirs）；单文件失败跳过不阻断；
 * 动态片段（foreach/if）标记待运行时补齐；目录不可达按 BR-004 抛 44008 降级纯 Schema。</p>
 */
@Slf4j
@Service
public class KbMapperParseService {

    /** 目标表识别（from/into/update/join/table 后紧跟表名） */
    private static final Pattern TARGET_TABLE = Pattern.compile(
            "(?i)\\b(?:from|into|update|join|table)\\s+([`\"\\[]?[\\w$]+[`\"\\]]?)");

    /** 动态标签集合（与元素树子节点检测配套；getTextContent 不含子元素标签，不能靠文本正则匹配） */
    private static final Set<String> DYNAMIC_TAG_NAMES = Set.of(
            "if", "foreach", "where", "set", "choose", "when", "otherwise");

    private final KbCrawlerProperties properties;
    private final KbStorageReviewAdapter adapter;

    public KbMapperParseService(KbCrawlerProperties properties, KbStorageReviewAdapter adapter) {
        this.properties = properties;
        this.adapter = adapter;
    }

    /**
     * 解析配置的 mapper 代码快照目录（可能多个目录，递归扫描 .xml），
     * 并应用知识写库（列注释补充 + biz_link）。
     *
     * @param dsId 数据源（写库隔离；scan-dirs 为该 ds 代码快照目录）
     * @return 解析结果聚合（statements/失败数），供任务 summary 展示
     */
    public MapperParseResult parseAndApply(String dsId) {
        List<String> dirs = properties.getMapper() == null ? Collections.emptyList()
                : properties.getMapper().getScanDirs();
        if (dirs == null || dirs.isEmpty()) {
            throw new BusinessException(KbCrawlerConstant.CODE_REPO_UNREACHABLE,
                    "未配置 mapper 代码目录(agenthub.kbcrawler.mapper.scan-dirs)，无法执行静态解析");
        }
        List<File> xmlFiles = new ArrayList<>();
        for (String dir : dirs) {
            File root = new File(dir);
            if (!root.exists() || !root.isDirectory()) {
                throw new BusinessException(KbCrawlerConstant.CODE_REPO_UNREACHABLE,
                        "代码仓库目录不可达: " + root.getAbsolutePath());
            }
            collectXmlFiles(root, xmlFiles);
        }
        MapperParseResult aggregate = new MapperParseResult();
        aggregate.setMapperFile(dirs.toString());
        aggregate.setNamespace("aggregate");
        int failed = 0;
        List<MapperParseResult> parsedFiles = new ArrayList<>();
        for (File xml : xmlFiles) {
            MapperParseResult one = parseMapperXml(xml);
            if (one == null) {
                failed++;
                continue;
            }
            aggregate.getStatements().addAll(one.getStatements());
            aggregate.getResultMaps().addAll(one.getResultMaps());
            parsedFiles.add(one);
            if (Integer.valueOf(1).equals(one.getParseFailed())) {
                failed++;
            }
        }
        aggregate.setParseFailed(failed);
        // 解析成功的文件统一应用写库（内部仍按文件隔离，单文件失败不影响其他）
        for (MapperParseResult parsed : parsedFiles) {
            if (Integer.valueOf(1).equals(parsed.getParseFailed())) {
                continue;
            }
            applyParseResult(dsId, parsed);
        }
        return aggregate;
    }

    /** 单个 mapper XML 解析（失败返回对象且 parseFailed=1；不抛异常阻断） */
    public MapperParseResult parseMapperXml(File xmlFile) {
        MapperParseResult result = new MapperParseResult();
        result.setMapperFile(xmlFile.getAbsolutePath());
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            Element root = doc.getDocumentElement();
            if (root == null || !"mapper".equals(root.getTagName())) {
                result.setParseFailed(1);
                result.setParseError("非 MyBatis mapper XML 根节点");
                return result;
            }
            result.setNamespace(root.getAttribute("namespace"));

            // resultMap：id/type/column→property
            NodeList resultMaps = root.getElementsByTagName("resultMap");
            for (int i = 0; i < resultMaps.getLength(); i++) {
                Element rm = (Element) resultMaps.item(i);
                MapperParseResult.ResultMapModel model = new MapperParseResult.ResultMapModel();
                model.setId(rm.getAttribute("id"));
                model.setType(rm.getAttribute("type"));
                NodeList mappings = rm.getElementsByTagName("*");
                for (int j = 0; j < mappings.getLength(); j++) {
                    Node node = mappings.item(j);
                    if (node instanceof Element el) {
                        String tag = el.getTagName();
                        if ("id".equals(tag) || "result".equals(tag)) {
                            MapperParseResult.ColumnMapping cm = new MapperParseResult.ColumnMapping();
                            cm.setColumn(el.getAttribute("column"));
                            cm.setProperty(el.getAttribute("property"));
                            if (cm.getColumn() != null && !cm.getColumn().isBlank()) {
                                model.getMappings().add(cm);
                            }
                        }
                    }
                }
                result.getResultMaps().add(model);
            }

            // statements：select/insert/update/delete
            result.getStatements().addAll(parseStatementNodes(root, "select"));
            result.getStatements().addAll(parseStatementNodes(root, "insert"));
            result.getStatements().addAll(parseStatementNodes(root, "update"));
            result.getStatements().addAll(parseStatementNodes(root, "delete"));
            result.setParseFailed(0);
            return result;
        } catch (Exception e) {
            result.setParseFailed(1);
            result.setParseError(truncate(e.getMessage(), 500));
            log.warn("kbcrawler: mapper XML 解析失败跳过 file={}: {}", xmlFile.getName(), e.getMessage());
            return result;
        }
    }

    private List<MapperStatementModel> parseStatementNodes(Element root, String tag) {
        List<MapperStatementModel> list = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            MapperStatementModel model = new MapperStatementModel();
            model.setId(el.getAttribute("id"));
            model.setStatementType(tag);
            String sqlText = el.getTextContent();
            model.setSqlText(truncate(sqlText, 4000));
            model.setDynamicUnresolved(containsDynamicTag(el));
            model.setRelatedTables(extractTables(sqlText));
            if (!model.getRelatedTables().isEmpty()) {
                model.setTargetTable(model.getRelatedTables().get(0));
            }
            list.add(model);
        }
        return list;
    }

    /**
     * 动态标签判定：基于 statement 元素树子节点检测（DOM 元素），
     * 而不是对 {@code getTextContent()} 文本做正则——getTextContent() 只拼接文本节点，
     * 子元素（&lt;if&gt;/&lt;foreach&gt; 等）标签本身不会进入文本，故文本正则永远无法命中。
     */
    private boolean containsDynamicTag(Element statementEl) {
        NodeList all = statementEl.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element e && DYNAMIC_TAG_NAMES.contains(e.getTagName())) {
                return true;
            }
        }
        return false;
    }

    /** 递归收集目录下 mapper XML */
    private void collectXmlFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectXmlFiles(f, out);
            } else if (f.isFile() && f.getName().endsWith(KbCrawlerConstant.MAPPER_XML_SUFFIX)) {
                out.add(f);
            }
        }
    }

    /** 从 SQL 文本提取涉及表（静态识别字面 FROM/JOIN/INTO/UPDATE/TABLE，动态片段不展开） */
    private List<String> extractTables(String sql) {
        Set<String> set = new LinkedHashSet<>();
        Matcher m = TARGET_TABLE.matcher(sql == null ? "" : sql);
        while (m.find()) {
            String t = cleanTable(m.group(1));
            if (t != null) {
                set.add(t);
            }
        }
        return new ArrayList<>(set);
    }

    private String cleanTable(String raw) {
        String t = raw.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        return t.isEmpty() || "DUAL".equalsIgnoreCase(t) ? null : t;
    }

    /**
     * 单个文件解析结果应用写库：
     * <ol>
     *   <li>resultMap column→property 映射作为列注释补充语义（同列按表只补一次，避免重复 update）；</li>
     *   <li>statement 生成 biz_link（biz_operation=statementId，dao_class=namespace，related_tables）。</li>
     * </ol>
     * 单文件内部异常不回滚整体（BR：解析失败文件跳过）。
     */
    private void applyParseResult(String dsId, MapperParseResult parsed) {
        // 1) 列注释补充：resultMap 列→属性（column 与 property 不同才需要语义提示）
        Map<String, String> colToProp = new LinkedHashMap<>();
        for (MapperParseResult.ResultMapModel rm : parsed.getResultMaps()) {
            for (MapperParseResult.ColumnMapping cm : rm.getMappings()) {
                if (cm.getColumn() != null && cm.getProperty() != null
                        && !cm.getColumn().equalsIgnoreCase(cm.getProperty())) {
                    colToProp.putIfAbsent(cm.getColumn(), cm.getProperty());
                }
            }
        }
        // 2) 收集语句：目标表 + 链路
        Set<String> relatedTables = new LinkedHashSet<>();
        for (MapperStatementModel stmt : parsed.getStatements()) {
            if (stmt.getTargetTable() == null) {
                continue;
            }
            relatedTables.add(stmt.getTargetTable());
            try {
                adapter.saveBizLink(dsId, stmt.getId(), "", parsed.getNamespace(),
                        stmt.getRelatedTables(),
                        "mapper-parse file=" + new File(parsed.getMapperFile()).getName());
            } catch (Exception e) {
                log.debug("kbcrawler: biz_link 写库跳过 file={} stmt={}: {}",
                        parsed.getMapperFile(), stmt.getId(), e.getMessage());
            }
        }
        if (colToProp.isEmpty() || relatedTables.isEmpty()) {
            return;
        }
        // 3) 列注释补充（按 表×列 幂等：同一表列只补一次）
        Set<String> done = new LinkedHashSet<>();
        for (String table : relatedTables) {
            for (Map.Entry<String, String> e : colToProp.entrySet()) {
                String dedupe = table + ":" + e.getKey();
                if (!done.add(dedupe)) {
                    continue;
                }
                try {
                    adapter.supplementColumnComment(dsId, null, table, e.getKey(),
                            "字段映射:" + e.getValue(), "mapper-parse column-semantic");
                } catch (Exception ex) {
                    log.debug("kbcrawler: 列注释补充跳过 table={} column={}: {}",
                            table, e.getKey(), ex.getMessage());
                }
            }
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
