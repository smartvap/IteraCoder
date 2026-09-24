package com.agenthub.ai.datasource.mapper;

import com.agenthub.ai.datasource.entity.DsConfigHealth;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * t_ds_config_health 健康历史表 Mapper（XML 分页倒序 / 最新一条）。
 *
 * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）。</p>
 */
@Mapper
public interface DsConfigHealthMapper extends BaseMapper<DsConfigHealth> {

    /**
     * 按 ds_id/check_type 分页查健康历史（倒序）。
     *
     * @param page MP 分页参数（首参触发拦截器，Service 侧已 clamp）
     */
    IPage<DsConfigHealth> selectPageHealths(IPage<DsConfigHealth> page,
                                            @Param("dsId") String dsId,
                                            @Param("checkType") String checkType);

    /**
     * 取某数据源最近一条健康/测试记录（详情聚合最近健康摘要用）。
     */
    DsConfigHealth selectLatestByDs(@Param("dsId") String dsId);
}
