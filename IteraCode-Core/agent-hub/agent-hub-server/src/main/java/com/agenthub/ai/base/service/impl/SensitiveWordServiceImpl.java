package com.agenthub.ai.base.service.impl;

import com.agenthub.ai.base.entity.SensitiveWord;
import com.agenthub.ai.base.mapper.SensitiveWordMapper;
import com.agenthub.ai.base.service.SensitiveWordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 敏感词服务实现——使用内存缓存 + AC 自动机（简化版：逐词匹配）
 */
@Slf4j
@Service
public class SensitiveWordServiceImpl
        extends ServiceImpl<SensitiveWordMapper, SensitiveWord>
        implements SensitiveWordService {

    /** 启用中的敏感词缓存（CopyOnWrite 保证读安全） */
    private final CopyOnWriteArrayList<String> wordCache = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        try {
            refreshCache();
        } catch (Exception e) {
            log.warn("敏感词缓存初始化失败（表可能尚未创建，将由 DatabaseInitializer 自动建表后通过 CRUD 操作触发刷新）: {}", e.getMessage());
        }
    }

    @Override
    public List<String> check(String text) {
        if (text == null || text.isEmpty() || wordCache.isEmpty()) return List.of();
        String lower = text.toLowerCase();
        List<String> matched = new ArrayList<>();
        for (String word : wordCache) {
            if (lower.contains(word.toLowerCase())) {
                matched.add(word);
            }
        }
        return matched;
    }

    @Override
    public void refreshCache() {
        List<String> words = list(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getEnabled, 1)
                .select(SensitiveWord::getWord))
                .stream()
                .map(SensitiveWord::getWord)
                .collect(Collectors.toList());
        wordCache.clear();
        wordCache.addAll(words);
        log.info("敏感词缓存已刷新，共 {} 个启用中的敏感词", wordCache.size());
    }
}
