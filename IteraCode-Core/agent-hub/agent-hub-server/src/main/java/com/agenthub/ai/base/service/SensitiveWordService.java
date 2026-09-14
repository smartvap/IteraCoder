package com.agenthub.ai.base.service;

import com.agenthub.ai.base.entity.SensitiveWord;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 敏感词服务接口
 */
public interface SensitiveWordService extends IService<SensitiveWord> {

    /**
     * 检查文本是否包含敏感词
     * @return 匹配到的敏感词列表，空列表表示通过
     */
    List<String> check(String text);

    /**
     * 刷新敏感词缓存
     */
    void refreshCache();
}
