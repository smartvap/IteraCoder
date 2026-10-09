package com.agenthub.ai.rcagent.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * B 库（llm-wiki）回写出站任务（fb_wiki_writeback）。
 *
 * <p>由“审核通过后的回写执行”按 suggestion 创建；FAQ/概念/指标口径沉淀页需
 * createPage/updatePage + syncPages（复用存量 LlmWikiClient）。远端不可用时任务保持
 * PENDING/FAILED 等待调度器重试（指数退避），不阻塞库 A（data-model 1.2）。</p>
 */
@Data
@TableName(value = "fb_wiki_writeback")
public class WikiWritebackTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID */
    private String dsId;

    /** 关联反馈（kb_feedback.id，溯源） */
    private Long feedbackId;

    /** 关联审核任务（kb_review_task.id，溯源） */
    private Long reviewTaskId;

    /** 对应 suggestions 快照下标 */
    private Integer suggestionNo;

    /** llm-wiki 空间名 */
    private String wikiSpace;

    /** 页面路径（可含子目录，如 concepts/投诉量口径.md） */
    private String pagePath;

    /** 页面标题（FAQ 标题/概念名，可空） */
    private String pageTitle;

    /** 页面正文 Markdown（MEDIUMTEXT） */
    private String pageContent;

    /** 页面类型 FAQ/CONCEPT/METRIC_CALIBER */
    private String pageType;

    /** 出站状态 PENDING/RUNNING/SUCCESS/FAILED/CANCELED */
    private String status;

    /** llm-wiki 异步 sync 任务 id */
    private String remoteTaskId;

    /** 重试次数 */
    private Integer retryCount;

    /** 最近错误（截断） */
    private String lastError;

    /** 下次重试时间（指数退避） */
    private Date nextRetryTime;

    private Date createTime;

    private Date updateTime;
}
