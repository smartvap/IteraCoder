package com.agenthub.ai.datasource.service;

import com.agenthub.ai.datasource.adapter.DsConfigHealthAdapter;
import com.agenthub.ai.datasource.vo.DbTypeOptionVO;
import com.agenthub.ai.dbaccess.model.DbType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源元数据字典服务（FR-BE-008 db-types）。
 */
@Service
public class DsMetaService {

    private final DsConfigHealthAdapter healthAdapter;

    public DsMetaService(DsConfigHealthAdapter healthAdapter) {
        this.healthAdapter = healthAdapter;
    }

    /**
     * 支持库类型字典（含 providerReady：未就绪可登记但不可启用）。
     */
    public List<DbTypeOptionVO> dbTypes() {
        List<DbTypeOptionVO> list = new ArrayList<>();
        for (DbType type : DbType.values()) {
            DbTypeOptionVO vo = new DbTypeOptionVO();
            vo.setDbType(type.getCode());
            vo.setLabel(type.getDisplayName());
            vo.setProviderReady(healthAdapter.isSupportedDbType(type.getCode()));
            list.add(vo);
        }
        return list;
    }
}
