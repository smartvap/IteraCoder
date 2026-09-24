package com.agenthub.ai.datasource.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.datasource.adapter.DsSpaceAdapter;
import com.agenthub.ai.datasource.adapter.SpaceLinker;
import com.agenthub.ai.datasource.constant.AuditAction;
import com.agenthub.ai.datasource.constant.DsErrorCode;
import com.agenthub.ai.datasource.constant.SpaceBindState;
import com.agenthub.ai.datasource.dto.SpaceBindRequest;
import com.agenthub.ai.datasource.entity.DsConfig;
import com.agenthub.ai.datasource.exception.DsSpaceLinkUnavailableException;
import com.agenthub.ai.datasource.mapper.DsConfigManageMapper;
import com.agenthub.ai.datasource.vo.DsSpaceShareItemVO;
import com.agenthub.ai.datasource.vo.DsSpaceShareVO;
import com.agenthub.ai.datasource.vo.SpaceBindResultVO;
import com.agenthub.ai.datasource.vo.SpaceOptionVO;
import com.agenthub.ai.llmwiki.client.LlmWikiClientException;
import com.agenthub.ai.llmwiki.dto.SpaceVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * llm-wiki 知识空间联动服务（FR-BE-006 / FR-003）。
 *
 * <p>bind：手动绑定/重试，同一空间可被多个数据源共享复用（原 43002 同名空间冲突分支已移除），
 * llm-wiki 不可用/创建失败降级 43006（BIND_PENDING/BIND_FAILED 语义不变）；
 * options：现有知识空间候选，所有候选均可选（available 恒 true），并附带共享数据源计数/清单。</p>
 *
 * <p>共享聚合统一委托 {@link DsSpaceSharingService}（只读、无 DDL）。</p>
 */
@Slf4j
@Service
public class DsSpaceLinkService {

    private final DsConfigManageMapper configManageMapper;
    private final SpaceLinker spaceLinker;
    private final DsSpaceAdapter spaceAdapter;
    private final DsAuditService auditService;
    private final DsSpaceSharingService spaceSharingService;

    public DsSpaceLinkService(DsConfigManageMapper configManageMapper,
                              SpaceLinker spaceLinker,
                              DsSpaceAdapter spaceAdapter,
                              DsAuditService auditService,
                              DsSpaceSharingService spaceSharingService) {
        this.configManageMapper = configManageMapper;
        this.spaceLinker = spaceLinker;
        this.spaceAdapter = spaceAdapter;
        this.auditService = auditService;
        this.spaceSharingService = spaceSharingService;
    }

    /**
     * 手动空间绑定/重试（api-contract 4）。
     *
     * <p>成功路径区分「本次新建」/「复用已有」/「复用共享」，审计摘要含共享数据源清单（脱敏）。</p>
     *
     * @throws BusinessException 43001 不存在 / 43009 空间名为空
     * @throws DsSpaceLinkUnavailableException 43006 llm-wiki 不可用（已降级保存 BIND_PENDING/BIND_FAILED）
     */
    public SpaceBindResultVO bind(String dsId, SpaceBindRequest dto, Long operatorId) {
        DsConfig config = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .eq(DsConfig::getIsDeleted, 0));
        if (config == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在或已删除");
        }
        if (dto == null || dto.getSpaceName() == null || dto.getSpaceName().isBlank()) {
            throw new BusinessException(DsErrorCode.SPACE_NAME_REQUIRED, "spaceName 必填");
        }
        String spaceName = dto.getSpaceName().trim();
        boolean autoCreate = dto.getAutoCreate() == null || dto.getAutoCreate();

        SpaceBindResultVO result = spaceLinker.bindOrCreate(dsId, spaceName, autoCreate, dto.getSpaceTypes());

        // 无论 BOUND 或降级 PENDING/FAILED 均记录目标空间名，保证后续重试语义一致
        updateConfigSpaceName(dsId, spaceName);

        if (SpaceBindState.BOUND.name().equals(result.getSpaceBindState())) {
            auditService.writeAudit(dsId, AuditAction.SPACE_BIND.name(), operatorId, null,
                    DsSpaceSharingService.buildSpaceBindSummary(Boolean.TRUE.equals(result.getCreated()),
                            result.getSharedDsList()),
                    null, "SUCCESS", null);
            return result;
        }
        // 降级保存 BIND_PENDING/BIND_FAILED：返回 43006 + data（controller 构造带诊断错误响应）
        auditService.writeAudit(dsId, AuditAction.SPACE_BIND.name(), operatorId, null,
                "空间绑定降级：" + result.getSpaceBindState(), null, "FAIL",
                result.getError());
        throw new DsSpaceLinkUnavailableException(result, result.getError());
    }

    /**
     * 空间候选列表（api-contract 5）。llm-wiki 不可用时返回空列表（不报错）。
     *
     * <p>语义变更：所有远端空间均可被选用/复用（{@code available} 恒 true），并附带该空间被多少
     * 非 DELETED 数据源共享（{@code boundDsCount}/{@code boundDsList}/{@code shareStatus}/
     * {@code reuseRecommended}）；{@code boundDs} 保留为共享清单首个 dsId（向前兼容）。</p>
     */
    public List<SpaceOptionVO> spaceOptions() {
        // 一次只读聚合（selectBoundSpaces）+ 一次 llm-wiki listSpaces，查询次数与存量一致
        Map<String, DsSpaceShareVO> shareGroup = spaceSharingService.groupBySpace();
        List<SpaceVO> remote;
        try {
            remote = spaceAdapter.listSpaces();
        } catch (LlmWikiClientException e) {
            log.debug("llm-wiki 空间候选不可用：{}", e.getMessage());
            return new ArrayList<>();
        }
        if (remote == null) {
            return new ArrayList<>();
        }
        List<SpaceOptionVO> options = new ArrayList<>();
        for (SpaceVO space : remote) {
            SpaceOptionVO vo = new SpaceOptionVO();
            vo.setName(space.name());
            vo.setPageCount(space.pageCount());
            vo.setCollectionName(space.collectionName());
            // available 恒 true：候选空间不再因「已被其他数据源持有」而不可选（字段保留向前兼容）
            vo.setAvailable(true);
            DsSpaceShareVO share = shareGroup.get(space.name());
            if (share == null) {
                vo.setBoundDs(null);
                vo.setBoundDsCount(0);
                vo.setBoundDsList(Collections.emptyList());
                vo.setShareStatus(DsSpaceSharingService.SHARE_STATUS_FREE);
                vo.setReuseRecommended(false);
            } else {
                List<DsSpaceShareItemVO> items = share.getBoundDsList();
                vo.setBoundDsCount(share.getBoundDsCount());
                vo.setBoundDsList(items == null ? Collections.emptyList() : items);
                vo.setBoundDs(items == null || items.isEmpty() ? null : items.get(0).getDsId());
                vo.setShareStatus(share.getShareStatus());
                vo.setReuseRecommended(share.getBoundDsCount() != null && share.getBoundDsCount() >= 1);
            }
            options.add(vo);
        }
        return options;
    }

    /**
     * llm-wiki 服务是否可用（空候选列表时用于区分「无空间」与「不可用」）。
     */
    public boolean spaceServiceAvailable() {
        return spaceAdapter.available();
    }

    private void updateConfigSpaceName(String dsId, String spaceName) {
        LambdaUpdateWrapper<DsConfig> wrapper = new LambdaUpdateWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .set(DsConfig::getSpaceName, spaceName);
        configManageMapper.update(null, wrapper);
    }
}
