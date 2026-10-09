package com.agenthub.ai.rcagent.engine.support;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceCandidateVO;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RCAgent 候选数据源分组校验器（纯逻辑，无状态，便于单测）。
 *
 * <p>覆盖 api-contract.md §3.4.4 与 data-model.md §7 的四类校验：
 * 空集、同组（system_code 一致）、数量上限、存在性/ACTIVE。失败一律
 * {@link BusinessException}({@link ErrorCode#PARAMS_ERROR}, 中文提示)，不启动工作流、不落库。</p>
 */
@Component
public class RcaSourceGroupValidator {

    /** 候选源集合非空校验（解析为空集 → 40000） */
    public void validateNotEmpty(List<RcaSourceCandidateVO> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选数据源为空，请检查系统下的启用数据源");
        }
    }

    /**
     * 数量上限校验（超限拒绝、不静默截断）。
     *
     * @param size         去重后候选源数量
     * @param maxCandidates 上限（来自 {@code agenthub.rcagent.source.max-candidates}，默认 4）
     */
    public void validateWithinLimit(int size, int maxCandidates) {
        if (size > maxCandidates) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "候选数据源数量超过上限（最多 " + maxCandidates + " 个），请减少选择");
        }
    }

    /**
     * 同组校验：全部候选源必须同属一个 {@code system_code}。
     * {@code system_code} 为空视为「未分组」，多个未分组源视为同组（不误报）。
     */
    public void validateSameSystem(List<RcaSourceCandidateVO> candidates) {
        Set<String> systemCodes = new HashSet<>();
        if (candidates != null) {
            for (RcaSourceCandidateVO c : candidates) {
                systemCodes.add(normalizeCode(c.getSystemCode()));
            }
        }
        if (systemCodes.size() > 1) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选数据源必须属于同一系统（system_code）");
        }
    }

    /**
     * 存在性/ACTIVE 校验（显式子集路径）：请求的每个 dsId 都必须在候选源中命中，
     * 否则以首个未命中项给出中文提示（api-contract.md §3.4.4）。
     */
    public void validateAllExist(List<String> requestedDsIds, List<RcaSourceCandidateVO> found) {
        Set<String> foundIds = new HashSet<>();
        if (found != null) {
            for (RcaSourceCandidateVO c : found) {
                foundIds.add(c.getDsId());
            }
        }
        if (requestedDsIds != null) {
            for (String dsId : requestedDsIds) {
                if (!foundIds.contains(dsId)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选数据源不存在或未启用：" + dsId);
                }
            }
        }
    }

    /** 单源存在性校验（dsId 路径）：查询结果为 null 即不存在或非 ACTIVE */
    public void validateExists(String dsId, RcaSourceCandidateVO candidate) {
        if (candidate == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选数据源不存在或未启用：" + dsId);
        }
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim();
    }
}
