package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.entity.SensitiveWord;
import com.agenthub.ai.base.service.SensitiveWordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 敏感词管理 API
 */
@RestController
@RequestMapping("/api/v1/sensitive-word")
@RequiredArgsConstructor
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    /** 分页查询 */
    @GetMapping("/page")
    public BaseResponse<IPage<SensitiveWord>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        LambdaQueryWrapper<SensitiveWord> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(SensitiveWord::getWord, keyword);
        }
        if (category != null && !category.isBlank()) {
            qw.eq(SensitiveWord::getCategory, category);
        }
        qw.orderByDesc(SensitiveWord::getUpdateTime);
        return ResultUtils.success(sensitiveWordService.page(new Page<>(pageNum, pageSize), qw));
    }

    /** 新增 */
    @PostMapping
    public BaseResponse<String> add(@RequestBody SensitiveWord word) {
        word.setId(null);
        word.setCreateTime(new Date());
        word.setUpdateTime(new Date());
        if (word.getEnabled() == null) word.setEnabled(1);
        if (word.getLevel() == null) word.setLevel("block");
        if (word.getCategory() == null) word.setCategory("general");
        sensitiveWordService.save(word);
        sensitiveWordService.refreshCache();
        return ResultUtils.success("添加成功");
    }

    /** 修改 */
    @PutMapping
    public BaseResponse<String> update(@RequestBody SensitiveWord word) {
        word.setUpdateTime(new Date());
        sensitiveWordService.updateById(word);
        sensitiveWordService.refreshCache();
        return ResultUtils.success("修改成功");
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public BaseResponse<String> delete(@PathVariable Long id) {
        sensitiveWordService.removeById(id);
        sensitiveWordService.refreshCache();
        return ResultUtils.success("删除成功");
    }

    /** 批量删除 */
    @DeleteMapping("/batch")
    public BaseResponse<String> batchDelete(@RequestBody List<Long> ids) {
        sensitiveWordService.removeByIds(ids);
        sensitiveWordService.refreshCache();
        return ResultUtils.success("批量删除成功");
    }

    /** 检查文本 */
    @PostMapping("/check")
    public BaseResponse<Map<String, Object>> check(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        List<String> matched = sensitiveWordService.check(text);
        return ResultUtils.success(Map.of("safe", matched.isEmpty(), "words", matched));
    }
}
