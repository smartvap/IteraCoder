package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * kb-schema-crawler 业务常量（错误码 44001~44008，集中定义避免魔法值散落）。
 */
public final class KbCrawlerConstant {

    private KbCrawlerConstant() {
    }

    /** 错误码 44001：采集数据源不存在/未注册 */
    public static final int DS_NOT_REGISTERED = 44001;
    /** 错误码 44002：采集类型不支持 */
    public static final int TASK_TYPE_UNSUPPORTED = 44002;
    /** 错误码 44003：任务状态冲突（运行中/已暂停不允许的触发/暂停/恢复） */
    public static final int TASK_STATE_CONFLICT = 44003;
    /** 错误码 44004：运行时采集未启用 */
    public static final int RUNTIME_DISABLED = 44004;
    /** 错误码 44005：上报数据非法（SQL 为空/含明文敏感参数） */
    public static final int REPORT_INVALID = 44005;
    /** 错误码 44006：变更检测无快照基线 */
    public static final int NO_SNAPSHOT_BASELINE = 44006;
    /** 错误码 44007：目标库 Provider/方言不可用 */
    public static final int PROVIDER_DIALECT_UNAVAILABLE = 44007;
    /** 错误码 44008：代码仓库不可达（Mapper 静态解析降级） */
    public static final int CODE_REPO_UNREACHABLE = 44008;

    /** 触发类型：手动（db 存储默认值 manual） */
    public static final String TRIGGER_MANUAL = "manual";

    /** 运行时通道未启用时的默认 remark 文案（接口2 降级说明） */
    public static final String REMARK_RUNTIME_DISABLED = "运行时采集未启用(拦截器默认关闭,接入方配置后开启)";

    /** mapper XML 文件扩展名 */
    public static final String MAPPER_XML_SUFFIX = ".xml";
    /** Java DAO 文件扩展名 */
    public static final String JAVA_SUFFIX = ".java";
}
