package com.agenthub.ai.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.pagehelper.Page;
import com.agenthub.ai.base.entity.User;
import com.agenthub.ai.base.pojo.dto.UserPageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
* 
* @description 针对表【user】的数据库操作Mapper
* @createDate
* @Entity com.agenthub.ai.entity.User
*/
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("select * from tb_user where user_name = #{userName}")
    User getByUsername(@Param("userName") String userName);

    Page<User> pageQuery(UserPageQueryDTO userPageQueryDTO);

    void updateUser(User user);
}




