package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 模型配置
 * @TableName model_config
 */
@TableName("model_config")
@Data
public class ModelConfigEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String ipAddress;
    private String configName;
    private String modelType;
    private String modelName;
    private String baseUrl;
    private String apiKey;
    private Double temperature;
    private Integer maxTokens;
    private Integer isActive;
    private Date createTime;
    private Date updateTime;
}
