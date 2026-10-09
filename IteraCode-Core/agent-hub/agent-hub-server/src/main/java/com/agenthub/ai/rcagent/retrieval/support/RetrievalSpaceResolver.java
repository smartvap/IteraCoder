package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ds_id ↔ llm-wiki space 映射解析（module-002 DataSourceRegistry.getConfig 只读）。
 *
 * <p>brownfield 保护：仅只读调用 module-002 实现，不改 dbaccess 代码；
 * 获取不到（未注册/模块未接入/异常）返回 null，由调用方以显式 spaceName 降级（SDD C.2 假设）。</p>
 */
@Slf4j
@Component
public class RetrievalSpaceResolver {

    private final DataSourceRegistry dataSourceRegistry;

    public RetrievalSpaceResolver(DataSourceRegistry dataSourceRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 按 ds_id 读取脱敏配置视图的 space_name。
     *
     * @return 空间名；未知数据源/未接入/异常返回 null（不抛出，允许显式 spaceName 降级）
     */
    public String resolveSpaceName(String dsId) {
        if (dsId == null || dsId.isBlank()) {
            return null;
        }
        try {
            DsConfigView view = dataSourceRegistry.getConfig(dsId);
            if (view == null || view.getSpaceName() == null || view.getSpaceName().isBlank()) {
                return null;
            }
            return view.getSpaceName();
        } catch (DbAccessException e) {
            log.debug("retrieval: 按 ds_id={} 解析 llm-wiki 空间不可用({})，允许显式 spaceName 降级",
                    dsId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("retrieval: DataSourceRegistry.getConfig 异常 ds_id={}，降级显式 spaceName", dsId);
            return null;
        }
    }
}
