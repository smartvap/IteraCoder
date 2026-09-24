package com.agenthub.ai.datasource.service;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.datasource.entity.DsConfigAudit;
import com.agenthub.ai.datasource.mapper.DsConfigAuditMapper;
import com.agenthub.ai.datasource.vo.AuditChange;
import com.agenthub.ai.datasource.vo.DsAuditVO;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源变更审计服务（FR-BE-003）。
 *
 * <p>敏感字段只记字段名与 changed 标记（BR-002），changeDetail 为脱敏 JSON 文本，
 * 任何审计记录不落密码/内嵌账密连接串明文。</p>
 */
@Slf4j
@Service
public class DsAuditService {

    private final DsConfigAuditMapper auditMapper;

    public DsAuditService(DsConfigAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * 写一条审计记录。
     *
     * @param bizResult SUCCESS/FAIL
     */
    public void writeAudit(String dsId, String action, Long operatorId, String operatorName,
                           String changeSummary, List<AuditChange> changeDetail,
                           String bizResult, String errorMessage) {
        DsConfigAudit audit = new DsConfigAudit();
        audit.setDsId(dsId);
        audit.setAction(action);
        audit.setOperatorId(operatorId == null ? 0L : operatorId);
        audit.setOperatorName(operatorName);
        audit.setChangeSummary(truncate(changeSummary, 512));
        audit.setChangeDetail(changeDetail == null || changeDetail.isEmpty() ? null
                : JSON.toJSONString(changeDetail));
        audit.setBizResult(bizResult == null ? "SUCCESS" : bizResult);
        audit.setErrorMessage(truncate(errorMessage, 512));
        audit.setCreateTime(new java.util.Date());
        try {
            auditMapper.insert(audit);
        } catch (Exception e) {
            // 审计失败不应阻断主链路（记录日志排查）
            log.error("数据源审计写入失败 dsId={} action={}", dsId, action, e);
        }
    }

    /**
     * 业务失败审计（独立事务 REQUIRES_NEW）。
     *
     * <p>状态类动作（enable/disable/delete/update）失败路径会抛出业务异常使调用方事务回滚；
     * 若失败审计写在调用方同一事务内会一并回滚导致无可追溯记录（FR-BE-003 完整性）。
     * 本方法开启独立事务提前提交，保证失败原因与 biz_result=FAIL 留痕不随业务回滚丢失。</p>
     *
     * @param dsId          数据源标识（路径参数必非空）
     * @param action        动作（ENABLE/DISABLE/DELETE/UPDATE）
     * @param operatorId    操作人
     * @param changeSummary 摘要（脱敏，不含密码/凭据）
     * @param errorMessage  失败原因（脱敏）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void writeAuditFailure(String dsId, String action, Long operatorId,
                                  String changeSummary, String errorMessage) {
        writeAudit(dsId, action, operatorId, null, changeSummary, null, "FAIL", errorMessage);
    }

    /**
     * 审计分页（倒序，脱敏明细解析为 AuditChange 列表）。
     */
    public PageResult auditLogs(String dsId, Integer page, Integer pageSize, String action) {
        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 50);
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<DsConfigAudit> result = auditMapper.selectPageAudits(new Page<>(p, ps), dsId, action);
        List<DsAuditVO> records = new ArrayList<>(result.getRecords().size());
        for (DsConfigAudit a : result.getRecords()) {
            DsAuditVO vo = new DsAuditVO();
            vo.setId(a.getId());
            vo.setDsId(a.getDsId());
            vo.setAction(a.getAction());
            vo.setOperatorId(a.getOperatorId());
            vo.setOperatorName(a.getOperatorName());
            vo.setChangeSummary(a.getChangeSummary());
            vo.setChangeDetail(parseDetail(a.getChangeDetail()));
            vo.setBizResult(a.getBizResult());
            vo.setErrorMessage(a.getErrorMessage());
            vo.setCreateTime(a.getCreateTime());
            records.add(vo);
        }
        return new PageResult(result.getTotal(), records);
    }

    private List<AuditChange> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseArray(json, AuditChange.class);
        } catch (Exception e) {
            log.debug("审计明细解析失败，按原始 JSON 字符串保留: {}", json);
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
