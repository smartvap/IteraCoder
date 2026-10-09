package com.agenthub.ai.rcagent.sqlguard.mapper;

import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * sql_guard_policy 策略 Mapper。
 */
@Mapper
public interface SqlGuardPolicyMapper extends BaseMapper<SqlGuardPolicy> {

    /**
     * 按作用域+ds_id 精确查询策略行（GLOBAL 行 ds_id 为空）。
     */
    SqlGuardPolicy selectByScopeAndDs(@Param("policyScope") String policyScope,
                                      @Param("dsId") String dsId);

    /**
     * 策略分页查询（不含已删除）。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<SqlGuardPolicy> selectPagePolicies(IPage<SqlGuardPolicy> page,
                                             @Param("policyScope") String policyScope,
                                             @Param("dsId") String dsId);

    /**
     * 策略总数（配合 selectPagePolicies 使用；MP 分页场景可省略，此处供非分页兜底）。
     */
    long countPolicies(@Param("policyScope") String policyScope,
                       @Param("dsId") String dsId);
}
