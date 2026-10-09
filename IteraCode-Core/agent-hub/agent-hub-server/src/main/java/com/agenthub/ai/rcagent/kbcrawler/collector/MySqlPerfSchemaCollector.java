package com.agenthub.ai.rcagent.kbcrawler.collector;

import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeSqlEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * MySQL performance_schema 字典辅助采集（预留/POC 骨架，FR-004 辅助证据）。
 *
 * <p>通过只读连接读取 performance_schema.events_statements_history 最近 SQL 文本；
 * 目标账号权限不足时返回空集合并降级（BR-003）。
 * MVP 骨架：真实 SQL 查询待 POC 数据源字典权限验证后完善，本实现默认返回空。</p>
 */
@Slf4j
@Component
public class MySqlPerfSchemaCollector {

    /**
     * 采集 MySQL performance_schema 最近执行 SQL（仅返回 SQL 文本，不含参数值）。
     *
     * @param dsId  数据源
     * @param limit 最大条数
     * @return SQL 事件（本地内存流转）；权限不足/查询失败返回空集合
     */
    public List<RuntimeSqlEventDTO> collectRecentSql(String dsId, int limit) {
        try {
            log.debug("kbcrawler: MySqlPerfSchemaCollector 骨架采集 dsId={}（待字典权限验证完善）", dsId);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("kbcrawler: MySQL performance_schema 辅助采集降级 dsId={}: {}", dsId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
