package com.agenthub.ai.datasource.mapper;

import com.agenthub.ai.datasource.entity.DsConfigAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * t_ds_config_audit 审计表 Mapper（XML 分页倒序）。
 *
 * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）。</p>
 */
@Mapper
public interface DsConfigAuditMapper extends BaseMapper<DsConfigAudit> {

    /**
     * 按 ds_id/action 分页查审计。
     *
     * @param page MP 分页参数（首参触发拦截器，Service 侧已 clamp）
     */
    IPage<DsConfigAudit> selectPageAudits(IPage<DsConfigAudit> page,
                                          @Param("dsId") String dsId,
                                          @Param("action") String action);
}
