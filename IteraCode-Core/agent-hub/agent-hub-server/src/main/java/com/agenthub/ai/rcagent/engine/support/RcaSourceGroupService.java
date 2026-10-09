package com.agenthub.ai.rcagent.engine.support;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.engine.mapper.RcaDsSourceMapper;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceCandidateVO;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceGroupVO;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceResolveVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 候选数据源分组解析服务（module-002 multi-source-session）。
 *
 * <p>{@code @Component} 无状态、线程安全；构造器注入只读 Mapper + 校验器。
 * 仅发 SELECT（复用 {@code idx_system_code}/{@code uk_ds_id}），零 DDL、不写任何表。</p>
 *
 * <p>统一保证：去重（trim 后按 ds_id 精确匹配、保留首次顺序）、主源在首
 * （恒等式 {@code DS_ID == DS_IDS[0]}）、退化（集合 ≤1 时 {@code DS_IDS=[DS_ID]}，全链路与现状一致）。
 * {@code start} 与 {@code sources/resolve} 复用同一入口 {@link #resolveCandidates}，保证同入参同结果。</p>
 */
@Slf4j
@Component
public class RcaSourceGroupService {

    /** ds_id / system_code 长度上限（api-contract.md §3.4.4） */
    private static final int MAX_ID_LENGTH = 64;

    /** 「未分组」展示名（system_code 为空时） */
    private static final String UNGROUPED_NAME = "未分组";

    private final RcaDsSourceMapper rcaDsSourceMapper;
    private final RcaSourceGroupValidator validator;

    /** 候选源数量上限（默认 4；缺配置按默认值执行，不启动失败，NFR-BE-006/SC-008） */
    @Value("${agenthub.rcagent.source.max-candidates:4}")
    private int maxCandidates;

    public RcaSourceGroupService(RcaDsSourceMapper rcaDsSourceMapper, RcaSourceGroupValidator validator) {
        this.rcaDsSourceMapper = rcaDsSourceMapper;
        this.validator = validator;
    }

    /**
     * 候选源统一解析入口（start 与 sources/resolve 复用）。
     *
     * <p>优先级 {@code dsIds > systemCode > dsId}；三者全缺 → 40000「请先选择数据源或系统」。
     * 入参长度与去重校验在此统一执行（项目未启用 Bean Validation，手动 if 校验）。</p>
     */
    public RcaSourceResolveVO resolveCandidates(String dsId, String systemCode, List<String> dsIds) {
        validateLength(dsId, "数据源 ID 过长");
        validateLength(systemCode, "系统编码过长");
        List<String> normalizedDsIds = normalizeDsIds(dsIds);
        if (normalizedDsIds.isEmpty() && isBlank(systemCode) && isBlank(dsId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先选择数据源或系统");
        }
        if (!normalizedDsIds.isEmpty()) {
            return resolveByDsIds(isBlank(dsId) ? null : dsId.trim(), normalizedDsIds);
        }
        if (!isBlank(systemCode)) {
            return resolveBySystemCode(systemCode.trim());
        }
        return resolveByDsId(dsId.trim());
    }

    /**
     * 统分：按 {@code system_code} 分组返回「系统 + 候选源清单」，供前端系统下拉与预览。
     *
     * @param includeUngrouped 是否返回 system_code 为空的「未分组」候选源集合（默认 false）
     */
    public List<RcaSourceGroupVO> listSystems(boolean includeUngrouped) {
        List<RcaSourceCandidateVO> all = rcaDsSourceMapper.selectActiveSystemGroups();
        Map<String, RcaSourceGroupVO> grouped = new LinkedHashMap<>();
        for (RcaSourceCandidateVO candidate : all) {
            String code = normalizeSystemCode(candidate);
            String key = code == null ? "" : code;
            RcaSourceGroupVO group = grouped.get(key);
            if (group == null) {
                group = new RcaSourceGroupVO();
                group.setSystemCode(code);
                group.setSystemName(code == null ? UNGROUPED_NAME : code);
                group.setCandidateDsIds(new ArrayList<>());
                group.setSources(new ArrayList<>());
                grouped.put(key, group);
            }
            group.getSources().add(candidate);
            group.getCandidateDsIds().add(candidate.getDsId());
        }

        List<RcaSourceGroupVO> result = new ArrayList<>();
        RcaSourceGroupVO ungrouped = null;
        for (RcaSourceGroupVO group : grouped.values()) {
            if (group.getSystemCode() == null) {
                ungrouped = group;
                continue;
            }
            group.setCandidateCount(group.getSources().size());
            result.add(group);
        }
        // 系统编码升序；「未分组」恒排最后
        result.sort(Comparator.comparing(RcaSourceGroupVO::getSystemCode));
        if (includeUngrouped && ungrouped != null) {
            ungrouped.setCandidateCount(ungrouped.getSources().size());
            result.add(ungrouped);
        }
        log.info("RCAgent 系统分组视图: systems={}, includeUngrouped={}", result.size(), includeUngrouped);
        return result;
    }

    /** 显式候选源子集：去重后校验上限/存在性/同组；主源（dsId 提供时）置首位 */
    private RcaSourceResolveVO resolveByDsIds(String primaryDsId, List<String> dedupedDsIds) {
        List<String> ordered = new ArrayList<>();
        if (primaryDsId != null) {
            ordered.add(primaryDsId);
        }
        for (String dsId : dedupedDsIds) {
            if (!ordered.contains(dsId)) {
                ordered.add(dsId);
            }
        }
        validator.validateWithinLimit(ordered.size(), maxCandidates);

        List<RcaSourceCandidateVO> found = rcaDsSourceMapper.selectActiveCandidatesByDsIds(ordered);
        validator.validateAllExist(ordered, found);
        validator.validateSameSystem(found);

        Map<String, RcaSourceCandidateVO> byId = new LinkedHashMap<>();
        for (RcaSourceCandidateVO candidate : found) {
            byId.put(candidate.getDsId(), candidate);
        }
        List<RcaSourceCandidateVO> sources = new ArrayList<>();
        for (String dsId : ordered) {
            sources.add(byId.get(dsId));
        }
        log.info("RCAgent 候选源解析（子集）: count={}", ordered.size());
        return buildResolveVO(normalizeSystemCode(sources.get(0)), ordered, sources);
    }

    /** 按系统全量展开：该 system_code 下全部 ACTIVE 源（按 ds_id 升序），主源取首项 */
    private RcaSourceResolveVO resolveBySystemCode(String systemCode) {
        List<RcaSourceCandidateVO> candidates = rcaDsSourceMapper.selectActiveCandidatesBySystemCode(systemCode);
        validator.validateNotEmpty(candidates);
        validator.validateWithinLimit(candidates.size(), maxCandidates);

        List<String> dsIds = new ArrayList<>();
        for (RcaSourceCandidateVO candidate : candidates) {
            dsIds.add(candidate.getDsId());
        }
        log.info("RCAgent 候选源解析（系统）: system={}, count={}", systemCode, candidates.size());
        return buildResolveVO(systemCode, dsIds, candidates);
    }

    /** 单源（向后兼容）：DS_IDS=[dsId]，主源=dsId，SYSTEM_CODE 取该 ds 的 system_code（可空） */
    private RcaSourceResolveVO resolveByDsId(String dsId) {
        RcaSourceCandidateVO candidate = rcaDsSourceMapper.selectActiveCandidateByDsId(dsId);
        validator.validateExists(dsId, candidate);
        List<String> dsIds = List.of(dsId);
        List<RcaSourceCandidateVO> sources = List.of(candidate);
        log.info("RCAgent 候选源解析（单源）: count=1");
        return buildResolveVO(normalizeSystemCode(candidate), dsIds, sources);
    }

    /** 组装解析结果（主源恒为首项，恒等式 DS_ID == DS_IDS[0]） */
    private RcaSourceResolveVO buildResolveVO(String systemCode, List<String> dsIds,
            List<RcaSourceCandidateVO> sources) {
        RcaSourceResolveVO vo = new RcaSourceResolveVO();
        vo.setSystemCode(systemCode);
        vo.setPrimaryDsId(dsIds.get(0));
        vo.setDsIds(dsIds);
        vo.setMultiSource(dsIds.size() > 1);
        vo.setCandidateCount(dsIds.size());
        vo.setSources(sources);
        return vo;
    }

    /** 去重（trim 后按 ds_id 精确匹配、保留首次顺序）+ 每个元素长度校验；空项忽略 */
    private List<String> normalizeDsIds(List<String> dsIds) {
        List<String> result = new ArrayList<>();
        if (dsIds == null) {
            return result;
        }
        for (String raw : dsIds) {
            if (raw == null) {
                continue;
            }
            String dsId = raw.trim();
            if (dsId.isEmpty()) {
                continue;
            }
            validateLength(dsId, "候选数据源 ID 过长");
            if (!result.contains(dsId)) {
                result.add(dsId);
            }
        }
        return result;
    }

    private static void validateLength(String value, String message) {
        if (value != null && value.length() > MAX_ID_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, message);
        }
    }

    /** system_code 归一化：空白 → null（未分组） */
    private static String normalizeSystemCode(RcaSourceCandidateVO candidate) {
        String code = candidate == null ? null : candidate.getSystemCode();
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
