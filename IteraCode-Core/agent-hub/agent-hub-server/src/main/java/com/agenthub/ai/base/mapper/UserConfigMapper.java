package com.agenthub.ai.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agenthub.ai.base.entity.UserConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户配置数据访问层
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，提供基础的 CRUD 操作。
 * 额外定义了根据用户ID查询配置的方法。</p>
 *
 * @see UserConfig
 */
@Mapper
public interface UserConfigMapper extends BaseMapper<UserConfig> {

    /**
     * 根据用户ID查询配置
     *
     * @param userId 用户ID
     * @return 用户配置对象，不存在则返回 null
     */
    @Select("select * from tb_user_config where user_id = #{userId}")
    UserConfig getByUserId(@Param("userId") Integer userId);
}
