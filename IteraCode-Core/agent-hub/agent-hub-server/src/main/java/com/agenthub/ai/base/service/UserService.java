package com.agenthub.ai.base.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.entity.User;
import com.agenthub.ai.base.pojo.dto.UserDTO;
import com.agenthub.ai.base.pojo.dto.UserPageQueryDTO;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.AccountNotFoundException;

/**
* 
* @description 针对表【user】的数据库操作Service
* @createDate
*/
public interface UserService extends IService<User> {

    User login(String userName, String password) throws AccountNotFoundException, AccountLockedException;

    void saveUser(UserDTO userDTO);

    PageResult pageQuery(UserPageQueryDTO userPageQueryDTO);

    void startOrStop(Integer status, Integer id);

    void updateUser(UserDTO userDTO);

    void register(User user);

    boolean getByUsername(String userName);
}
