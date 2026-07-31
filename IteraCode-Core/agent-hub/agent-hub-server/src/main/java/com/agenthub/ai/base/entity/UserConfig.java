package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户配置实体
 *
 * <p>对应数据库表 {@code tb_user_config}，存储用户的个性化配置信息。</p>
 *
 * <p>配置以 JSON 字符串形式存储在 {@link #configJson} 字段中，
 * 包括但不限于：主题颜色、模型选择、远程 API 地址、是否显示推理过程等。</p>
 *
 * <p>JSON 结构示例：</p>
 * <pre>
 * {
 *   "bgColor": "#f5f7fa",
 *   "menuBgColor": "#1A478A",
 *   "skipLogin": false,
 *   "modelType": "local",
 *   "apiUrl": "",
 *   "apiKey": "",
 *   "currentModel": "",
 *   "modelConfigs": [],
 *   "showReasoning": true
 * }
 * </pre>
 *
 * @see com.agenthub.ai.base.service.UserConfigService
 */
@TableName(value = "tb_user_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserConfig {
    /** 主键ID，自增 */
    @TableId
    private Integer id;

    /** 用户ID，关联 tb_user 表 */
    private Integer userId;

    /** 配置 JSON 字符串，存储全部用户设置 */
    private String configJson;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后更新时间 */
    private LocalDateTime updateTime;
}
