package com.agenthub.ai.rcagent.kbcrawler.collector;

import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeSqlEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Oracle v$sql 字典辅助采集（预留/POC 骨架，FR-004 辅助证据）。
 *
 * <p>通过只读连接读取 v$sql/v$sqlarea 最近 SQL 文本；目标账号权限不足时返回空集合并降级
 * （BR-003：字典辅助视为可选，不阻塞 POC，仅拦截器证据为主通道）。
 * MVP 骨架：真实 SQL 查询待 POC 数据源字典权限验证后完善，本实现默认返回空。</p>
 */
@Slf4j
@Component
public class OracleV$SqlCollector {

    /**
     * 采集 Oracle v$sql 最近执行 SQL（仅返回 SQL 文本，不含绑定变量值）。
     *
     * @param dsId        数据源
     * @param limit       最大条数（受 limits 约束）
     * @return SQL 事件（本地内存流转）；权限不足/查询失败返回空集合
     */
    public List<RuntimeSqlEventDTO> collectRecentSql(String dsId, int limit) {
        try {
            // POC 骨架：真实 v$sql 采集由目标 Oracle 只读账号权限验证后实现
            log.debug("kbcrawler: OracleV$SqlCollector 骨架采集 dsId={}（待字典权限验证完善）", dsId);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("kbcrawler: Oracle v$sql 辅助采集降级 dsId={}: {}", dsId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
