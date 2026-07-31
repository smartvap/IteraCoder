package com.agenthub.ai.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.agenthub.ai.base.entity.UserConfig;
import com.agenthub.ai.base.mapper.UserConfigMapper;
import com.agenthub.ai.base.service.UserConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户配置业务逻辑实现
 *
 * <p>实现 {@link UserConfigService} 接口，提供配置的查询和 upsert 操作。</p>
 *
 * @see UserConfigService
 */
@Service
public class UserConfigServiceImpl extends ServiceImpl<UserConfigMapper, UserConfig> implements UserConfigService {

    @Autowired
    private UserConfigMapper userConfigMapper;

    /**
     * 根据用户ID查询配置
     *
     * @param userId 用户ID
     * @return 用户配置对象，不存在则返回 null
     */
    @Override
    public UserConfig getByUserId(Integer userId) {
        return userConfigMapper.getByUserId(userId);
    }

    /**
     * 保存或更新用户配置
     *
     * <p>先查询用户是否已有配置记录，有则更新，无则插入新记录。</p>
     *
     * @param userId     用户ID
     * @param configJson 配置 JSON 字符串
     */
    @Override
    public void saveOrUpdateConfig(Integer userId, String configJson) {
        UserConfig config = userConfigMapper.getByUserId(userId);
        if (config == null) {
            // 首次保存，创建新记录
            config = UserConfig.builder()
                    .userId(userId)
                    .configJson(configJson)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            userConfigMapper.insert(config);
        } else {
            // 已有记录，更新配置和时间
            config.setConfigJson(configJson);
            config.setUpdateTime(LocalDateTime.now());
            userConfigMapper.updateById(config);
        }
    }
}
