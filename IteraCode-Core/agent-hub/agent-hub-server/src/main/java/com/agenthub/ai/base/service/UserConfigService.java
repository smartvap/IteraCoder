package com.agenthub.ai.base.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.agenthub.ai.base.entity.UserConfig;

/**
 * 用户配置业务逻辑接口
 *
 * <p>定义用户配置的查询和保存操作。</p>
 *
 * @see UserConfig
 */
public interface UserConfigService extends IService<UserConfig> {

    /**
     * 根据用户ID获取配置
     *
     * @param userId 用户ID
     * @return 用户配置对象，不存在则返回 null
     */
    UserConfig getByUserId(Integer userId);

    /**
     * 保存或更新用户配置
     *
     * <p>若用户已有配置则更新 configJson 和 updateTime，
     * 若不存在则新建一条配置记录。</p>
     *
     * @param userId    用户ID
     * @param configJson 配置 JSON 字符串
     */
    void saveOrUpdateConfig(Integer userId, String configJson);
}
