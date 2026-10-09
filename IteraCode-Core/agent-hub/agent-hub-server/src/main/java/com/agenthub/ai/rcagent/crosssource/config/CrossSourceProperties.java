package com.agenthub.ai.rcagent.crosssource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 跨源 join 配置（{@code agenthub.rcagent.cross-source.*}，module-003）。
 *
 * <p>与 {@code RetrievalProperties} 同款装配方式（{@code @Component + @ConfigurationProperties}）。
 * 字段与默认值逐条对照 {@code data-model.md §3.7}；**缺配置不启动失败**
 * （类内默认值即生产默认值，yml 缺省时行为一致，零 yml 改动也可运行）。</p>
 *
 * <p>一键回滚：{@link #enabled} = false 时永不进入跨源分支（走既有单源链路，零回归）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.rcagent.cross-source")
public class CrossSourceProperties {

    /** 跨源总开关；false → 永不进入跨源分支（一键回滚，零回归） */
    private boolean enabled = true;

    /** 参与 join 的源数上限；超出明确拒绝（不静默截断） */
    private int maxSources = 2;

    /** 每源行数上限（实际生效 = min(本值, ds limits, db-access 模块默认)，由既有执行层 clamp） */
    private int perSourceRowLimit = 2000;

    /** 合并后行数上限，超限截断并标注 */
    private int mergedRowLimit = 2000;

    /** 半连接下推开关 */
    private boolean semiJoinPushdownEnabled = true;

    /** 单条 IN (...) 元素上限（超出分片） */
    private int semiJoinInThreshold = 1000;

    /** 分片数上限（超出 → 回退不下推，仅内存 join） */
    private int semiJoinMaxShards = 10;

    /** join key 字符串比较是否忽略大小写（默认仅 trim） */
    private boolean joinKeyIgnoreCase = false;

    /** 计划文本/JSON 截断长度 */
    private int planMaxLen = 4000;

    /** 单源 SQL 截断长度（与既有 sql_generate 截断一致） */
    private int sourceSqlMaxLen = 20000;

    /** CROSS_SOURCE_NOTE 截断长度 */
    private int noteMaxLen = 500;

    /** 下推字面量（字符串）长度上限，超出剔除该 key */
    private int keyLiteralMaxLen = 64;
}
