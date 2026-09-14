package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 敏感词实体
 */
@Data
@TableName(value = "sensitive_word")
public class SensitiveWord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感词 */
    private String word;

    /** 分类（general/political/pornography/violence/custom）*/
    private String category;

    /** 级别：block（完全拦截）/ warn（警告但放行）*/
    private String level;

    /** 是否启用：1启用 0禁用 */
    private Integer enabled;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
