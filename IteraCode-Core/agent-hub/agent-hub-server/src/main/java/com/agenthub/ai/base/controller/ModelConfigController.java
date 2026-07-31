package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.entity.ModelConfigEntity;
import com.agenthub.ai.base.mapper.ModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/v1/model/config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigMapper mapper;

    @GetMapping
    public List<ModelConfigEntity> list(ServerHttpRequest request,
            @RequestParam(required = false) Long userId) {
        String ip = getClientIp(request);
        LambdaQueryWrapper<ModelConfigEntity> qw = new LambdaQueryWrapper<>();
        qw.eq(ModelConfigEntity::getIsActive, 1);
        if (userId != null) {
            qw.eq(ModelConfigEntity::getUserId, userId);
        } else {
            qw.isNull(ModelConfigEntity::getUserId)
              .eq(ModelConfigEntity::getIpAddress, ip);
        }
        qw.orderByAsc(ModelConfigEntity::getId);
        return mapper.selectList(qw);
    }

    @PostMapping
    public ModelConfigEntity add(@RequestBody ModelConfigEntity entity, ServerHttpRequest request) {
        String ip = getClientIp(request);
        if (entity.getUserId() == null) entity.setUserId(BaseContext.getCurrentId());
        entity.setIpAddress(ip);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        if (entity.getIsActive() == null) entity.setIsActive(1);
        mapper.insert(entity);
        return entity;
    }

    @PutMapping("/{id}")
    public ModelConfigEntity update(@PathVariable Long id, @RequestBody ModelConfigEntity entity) {
        entity.setId(id);
        entity.setUpdateTime(new Date());
        mapper.updateById(entity);
        return entity;
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return "ok";
    }

    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeaders().getFirst("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1";
        }
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip != null ? ip : "127.0.0.1";
    }
}
