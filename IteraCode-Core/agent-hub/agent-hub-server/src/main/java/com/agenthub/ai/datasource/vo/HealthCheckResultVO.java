package com.agenthub.ai.datasource.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 健康检查/测试连接结构化诊断结果（api-contract HealthCheckResultVO）。
 *
 * <p>全部字段脱敏：errorMessage 禁止地址凭据/密码；passwordForTest 不进入该对象。</p>
 */
@Data
public class HealthCheckResultVO {

    /** 数据源标识（存在时返回） */
    private String dsId;

    /** TEST_CONNECTION / HEALTH_CHECK */
    private String checkType;

    /** PASS/PARTIAL/FAIL */
    private String checkResult;

    /** 连通性 */
    private Boolean connectionOk;

    /** 方言识别结果 */
    private String dialectIdentified;

    /** 只读校验通过 */
    private Boolean readonlyOk;

    /** 字典权限探测通过 */
    private Boolean dictionaryOk;

    /** 白名单满足（三模式：未配置 null/ALL/DENY_ONLY（含空清单）/ALLOW_ONLY+非空清单均满足；仅显式 ALLOW_ONLY+空清单不满足） */
    private Boolean whitelistOk;

    /** 失败项枚举（connection/account/permission/driver/dictionary/whitelist） */
    private List<String> failureItems;

    /** 脱敏失败原因（不含地址凭据/密码）；内容为「类别中文 + 建议」（语义不变、文案细化） */
    private String errorMessage;

    /** 失败类别码（ConnectionFailureCategory.code；仅连接建立失败时非空，其余场景为 null）——本模块兼容新增 */
    private String failureCategory;

    /** 失败类别中文名（与 failureCategory 同时出现，便于前端直接展示）——本模块兼容新增 */
    private String failureCategoryText;

    /** 中文处置建议（脱敏，禁含 host/库名原文/账号/密码/连接串/驱动原文）——本模块兼容新增 */
    private String failureSuggestion;

    /** 耗时 ms */
    private Long costMs;

    /** 检查时间（历史记录返回） */
    private Date createTime;
}
