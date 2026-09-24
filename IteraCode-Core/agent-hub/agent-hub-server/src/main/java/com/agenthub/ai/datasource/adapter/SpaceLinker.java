package com.agenthub.ai.datasource.adapter;

import com.agenthub.ai.datasource.constant.SpaceBindState;
import com.agenthub.ai.datasource.entity.DsConfigAdmin;
import com.agenthub.ai.datasource.mapper.DsConfigAdminMapper;
import com.agenthub.ai.datasource.mapper.DsConfigManageMapper;
import com.agenthub.ai.datasource.vo.DsBoundSpaceVO;
import com.agenthub.ai.datasource.vo.SpaceBindResultVO;
import com.agenthub.ai.llmwiki.client.LlmWikiClientException;
import com.agenthub.ai.llmwiki.dto.SpaceVO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 知识空间绑定/复用决策器（FR-BE-006；原 BR-004 一对一占用拦截已废弃）。
 *
 * <p>绑定算法（N:1 共享复用）：llm-wiki 同名空间存在 → 复用 BOUND（**含该空间已被其他数据源持有的场景**，
 * 直接共享复用，不再判占用冲突）；不存在且 autoCreate → createSpace；llm-wiki 不可用/创建失败 →
 * 更新 admin 为 BIND_PENDING 降级返回；不存在且未 autoCreate → BIND_FAILED（仅真实失败保留）。
 * BOUND 成功路径额外聚合「共享该空间的其他非 DELETED 数据源 dsId 清单」，供调用方写审计摘要与前端提示。</p>
 */
@Slf4j
@Component
public class SpaceLinker {

    private final DsSpaceAdapter spaceAdapter;
    private final DsConfigManageMapper manageMapper;
    private final DsConfigAdminMapper adminMapper;

    public SpaceLinker(DsSpaceAdapter spaceAdapter,
                       DsConfigManageMapper manageMapper,
                       DsConfigAdminMapper adminMapper) {
        this.spaceAdapter = spaceAdapter;
        this.manageMapper = manageMapper;
        this.adminMapper = adminMapper;
    }

    /**
     * 绑定/复用/创建知识空间（同一空间可被多个数据源共享复用）。
     *
     * @param dsId       数据源标识
     * @param spaceName  目标空间名（非空）
     * @param autoCreate 空间不存在时是否自动创建
     * @param types      空间类型（可空）
     * @return 绑定结果；BIND_PENDING 表示 llm-wiki 不可用已降级（可稍后重试）；
     * BOUND 时 {@code sharedDsList} 为同空间其他数据源 dsId 清单（脱敏，可空），
     * {@code sharedDsCount} 为共享该空间的数据源总数（含自身）
     */
    public SpaceBindResultVO bindOrCreate(String dsId, String spaceName, boolean autoCreate,
                                          List<String> types) {
        SpaceBindResultVO result = new SpaceBindResultVO();
        result.setDsId(dsId);
        result.setSpaceName(spaceName);
        result.setCreated(false);

        // 1) 查 llm-wiki 现有空间（不可用降级 BIND_PENDING，不抛错）
        List<SpaceVO> remoteSpaces;
        try {
            remoteSpaces = spaceAdapter.listSpaces();
        } catch (LlmWikiClientException e) {
            log.warn("llm-wiki 空间服务不可用，dsId={} 降级 BIND_PENDING", dsId);
            markAdminBindState(dsId, SpaceBindState.BIND_PENDING.name(), "llm-wiki 空间服务不可用，可稍后重试");
            result.setSpaceBindState(SpaceBindState.BIND_PENDING.name());
            result.setError("llm-wiki 空间服务不可用，已降级为待绑定");
            return result;
        }

        // 2) 同名空间存在 → 复用（含被其他数据源持有的共享空间；不与任何 ds 冲突）
        Optional<SpaceVO> existing = remoteSpaces == null ? Optional.empty()
                : remoteSpaces.stream().filter(s -> spaceName.equals(s.name())).findFirst();
        if (existing.isPresent()) {
            markAdminBindState(dsId, SpaceBindState.BOUND.name(), null);
            result.setSpaceBindState(SpaceBindState.BOUND.name());
            result.setCreated(false);
            fillSharedDs(result, dsId, spaceName);
            return result;
        }

        // 3) 不存在：autoCreate 创建，否则 BIND_FAILED 提示
        if (autoCreate) {
            try {
                spaceAdapter.createSpace(spaceName, types);
                markAdminBindState(dsId, SpaceBindState.BOUND.name(), null);
                result.setSpaceBindState(SpaceBindState.BOUND.name());
                result.setCreated(true);
                fillSharedDs(result, dsId, spaceName);
                return result;
            } catch (LlmWikiClientException e) {
                log.warn("llm-wiki 创建空间失败，dsId={} space={} 降级 BIND_PENDING", dsId, spaceName);
                markAdminBindState(dsId, SpaceBindState.BIND_PENDING.name(), "llm-wiki 创建空间失败，可稍后重试");
                result.setSpaceBindState(SpaceBindState.BIND_PENDING.name());
                result.setError("llm-wiki 创建空间失败，已降级为待绑定");
                return result;
            }
        }
        markAdminBindState(dsId, SpaceBindState.BIND_FAILED.name(),
                "知识空间 " + spaceName + " 不存在且未开启自动创建");
        result.setSpaceBindState(SpaceBindState.BIND_FAILED.name());
        result.setError("知识空间不存在且未开启自动创建");
        return result;
    }

    /** 填充同空间共享数据源清单与总数（清单仅列其他 dsId，按升序，不含自身；总数为含自身口径） */
    private void fillSharedDs(SpaceBindResultVO result, String dsId, String spaceName) {
        List<String> sharedDsList = findSharedDs(dsId, spaceName);
        result.setSharedDsList(sharedDsList);
        // 共享数据源总数含自身（与 data-model V-01 boundDsCount 口径、api-contract「共享数据源总数」一致）
        result.setSharedDsCount(sharedDsList.size() + 1);
    }

    /**
     * 聚合同空间的其他非 DELETED 数据源 dsId（共享复用提示用）。
     *
     * <p>脱敏约束：仅返回 dsId，不带任何连接串/账号/密码引用。升序输出保证结果稳定。</p>
     */
    private List<String> findSharedDs(String dsId, String spaceName) {
        List<DsBoundSpaceVO> bound = manageMapper.selectBoundSpaces();
        if (bound == null || bound.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> dsIds = new ArrayList<>();
        for (DsBoundSpaceVO b : bound) {
            if (b == null || b.getDsId() == null) {
                continue;
            }
            if (spaceName.equals(b.getSpaceName()) && !dsId.equals(b.getDsId())) {
                dsIds.add(b.getDsId());
            }
        }
        Collections.sort(dsIds);
        return dsIds;
    }

    private void markAdminBindState(String dsId, String spaceBindState, String error) {
        LambdaUpdateWrapper<DsConfigAdmin> wrapper = new LambdaUpdateWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId)
                .set(DsConfigAdmin::getSpaceBindState, spaceBindState)
                .set(DsConfigAdmin::getSpaceBindError, error);
        adminMapper.update(null, wrapper);
    }
}
