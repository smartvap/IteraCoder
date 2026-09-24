package com.agenthub.ai.datasource.mapper;

import com.agenthub.ai.datasource.dto.DsConfigPageQueryDTO;
import com.agenthub.ai.datasource.entity.DsConfig;
import com.agenthub.ai.datasource.vo.DsBoundSpaceVO;
import com.agenthub.ai.datasource.vo.DsConfigVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * t_ds_config 管理壳 Mapper（BaseMapper 执行增改/启停/软删/乐观锁更新）。
 *
 * <p>列表分页需联查 t_ds_config_admin 的 manage_state/space_bind_state/健康摘要，走 XML。
 * 分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）。</p>
 */
@Mapper
public interface DsConfigManageMapper extends BaseMapper<DsConfig> {

    /**
     * 分页列表（联查管理扩展表）：admin 按 manage_state/system_code/db_type/keyword 过滤。
     *
     * @param page  分页参数（MP 分页拦截器以首参 IPage 触发；Service 侧已 clamp）
     * @param query 过滤条件；manageState=DELETED 时返回已软删行，否则默认排除已删
     */
    IPage<DsConfigVO> selectPageConfigs(IPage<DsConfigVO> page, @Param("query") DsConfigPageQueryDTO query);

    /**
     * 空间占用清单（BR-004：同名空间冲突提示用）。
     *
     * <p>口径：任一非 DELETED 数据源（DRAFT/DISABLED/ACTIVE）只要 space_name 非空即视为占用，
     * 避免仅统计 ACTIVE 时两个 DRAFT 重复绑定同一空间后双双启用破坏一对一；
     * 附带 manage_state 供 enable 前置 ACTIVE 占用校验使用。</p>
     *
     * @return 每行含 spaceName 与其绑定的 dsId/manageState
     */
    List<DsBoundSpaceVO> selectBoundSpaces();
}
