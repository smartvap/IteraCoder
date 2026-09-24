package com.agenthub.ai.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据源管理模块配置（agenthub.datasource.*）。
 *
 * <p>承载健康检查短超时、分页默认值与页大小上限，避免代码硬编码魔法值（backend-rules 5）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.datasource")
public class DsProperties {

    /** 健康/测试连接短超时（毫秒），默认 3000 */
    private int healthTimeoutMs = 3000;

    /** 健康/测试连接超时上限（毫秒），请求传值不得超过该上限 */
    private int healthTimeoutMaxMs = 10000;

    /** 分页默认每页条数 */
    private int defaultPageSize = 10;

    /** 分页页大小上限 */
    private int pageSizeMax = 50;
}
