package com.agenthub.ai.datasource.service;

import com.agenthub.ai.datasource.constant.DsManageState;
import com.agenthub.ai.datasource.mapper.DsConfigManageMapper;
import com.agenthub.ai.datasource.vo.DsBoundSpaceVO;
import com.agenthub.ai.datasource.vo.DsSpaceShareItemVO;
import com.agenthub.ai.datasource.vo.DsSpaceShareVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识空间共享分组只读聚合服务（FR-BE-007/F-03，data-model §3.1 V-01 / §3.2 V-02）。
 *
 * <p>业务背景：数据源 ↔ llm-wiki 知识空间的关系由 1:1（原 BR-004 占用拦截）放宽为 N:1 共享复用，
 * 本服务负责把「空间名 → 非 DELETED 数据源清单」这一共享聚合口径收敛到单一位置，供
 * {@link DsSpaceLinkService#spaceOptions()} 与管理页复用。**纯只读**：仅调用存量
 * {@code selectBoundSpaces}（SQL 不变），在内存按 {@code space_name} 分组，无任何写操作、无 DDL。</p>
 *
 * <p>统计口径：沿用存量查询过滤条件（{@code cfg.is_deleted=0}、{@code adm.is_deleted=0}、
 * {@code manage_state != 'DELETED'}、{@code space_name} 非空），即 DRAFT/DISABLED 同样计入共享数量
 * （与「绑定即共享」语义一致，仅 DELETED 不参与统计）。</p>
 */
@Service
public class DsSpaceSharingService {

    /** 共享状态：无数据源持有 */
    public static final String SHARE_STATUS_FREE = "FREE";

    /** 共享状态：仅 1 个数据源持有 */
    public static final String SHARE_STATUS_EXCLUSIVE = "EXCLUSIVE";

    /** 共享状态：≥2 个数据源共享 */
    public static final String SHARE_STATUS_SHARED = "SHARED";

    /** 审计摘要中最多列出的共享数据源数量（超出以「等 N 个」表述，控制长度） */
    private static final int SUMMARY_MAX_LISTED_DS = 5;

    /** 审计 change_summary 列长度上限（t_ds_config_audit.change_summary VARCHAR(512)） */
    private static final int SUMMARY_MAX_LENGTH = 512;

    private final DsConfigManageMapper configManageMapper;

    public DsSpaceSharingService(DsConfigManageMapper configManageMapper) {
        this.configManageMapper = configManageMapper;
    }

    /**
     * 全量空间共享分组（V-01）：空间名 → 共享视图。
     *
     * @return 空间名 → {@link DsSpaceShareVO}；无共享数据或无数据源绑定时返回空 Map（不返回 null）
     */
    public Map<String, DsSpaceShareVO> groupBySpace() {
        Map<String, DsSpaceShareVO> grouped = new LinkedHashMap<>();
        Map<String, List<DsBoundSpaceVO>> raw = new HashMap<>();
        List<DsBoundSpaceVO> bound = configManageMapper.selectBoundSpaces();
        if (bound != null) {
            for (DsBoundSpaceVO row : bound) {
                if (row == null || row.getSpaceName() == null || row.getSpaceName().isBlank()
                        || row.getDsId() == null) {
                    continue;
                }
                raw.computeIfAbsent(row.getSpaceName(), k -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<DsBoundSpaceVO>> entry : raw.entrySet()) {
            grouped.put(entry.getKey(), toShareVO(entry.getKey(), entry.getValue()));
        }
        return grouped;
    }

    /**
     * 单空间共享计数（V-02）：便捷查询入口（管理页/后续扩展）。
     *
     * @param spaceName 空间名（空白返回 null）
     * @return 该空间共享视图；未被任何数据源持有时返回 null
     */
    public DsSpaceShareVO countBySpace(String spaceName) {
        if (spaceName == null || spaceName.isBlank()) {
            return null;
        }
        return groupBySpace().get(spaceName);
    }

    /**
     * 空间绑定审计摘要（api-contract §3 接口 1/2/4、data-model §4.3）。
     *
     * <p>文案口径：新建空间 → 「自动创建知识空间并绑定成功」；复用且存在其他共享源 →
     * 「复用共享知识空间绑定成功（共享数据源：A,B）」；复用且无其他源 → 「复用已有知识空间绑定成功」。
     * **只列 dsId**（脱敏），最多列出 5 个，超出以「等 N 个」收尾，
     * 最终长度钳制到 512 以内（change_summary 列上限）。</p>
     */
    public static String buildSpaceBindSummary(boolean created, List<String> sharedDsList) {
        if (created) {
            return "自动创建知识空间并绑定成功";
        }
        if (sharedDsList == null || sharedDsList.isEmpty()) {
            return "复用已有知识空间绑定成功";
        }
        int listed = Math.min(sharedDsList.size(), SUMMARY_MAX_LISTED_DS);
        String names = String.join(",", sharedDsList.subList(0, listed));
        String tail = sharedDsList.size() > listed ? "等 " + sharedDsList.size() + " 个" : "";
        String summary = "复用共享知识空间绑定成功（共享数据源：" + names + tail + "）";
        return summary.length() > SUMMARY_MAX_LENGTH ? summary.substring(0, SUMMARY_MAX_LENGTH) : summary;
    }

    /** 单空间原始行 → 共享视图（清单按 dsId 升序稳定输出；dsName/systemCode 存量为空保留） */
    private DsSpaceShareVO toShareVO(String spaceName, List<DsBoundSpaceVO> rows) {
        List<DsBoundSpaceVO> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(DsBoundSpaceVO::getDsId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<DsSpaceShareItemVO> items = new ArrayList<>();
        int activeCount = 0;
        for (DsBoundSpaceVO row : sorted) {
            DsSpaceShareItemVO item = new DsSpaceShareItemVO();
            item.setDsId(row.getDsId());
            item.setManageState(row.getManageState());
            items.add(item);
            if (DsManageState.ACTIVE.name().equals(row.getManageState())) {
                activeCount++;
            }
        }
        DsSpaceShareVO vo = new DsSpaceShareVO();
        vo.setSpaceName(spaceName);
        vo.setBoundDsCount(items.size());
        vo.setActiveDsCount(activeCount);
        vo.setBoundDsList(items);
        vo.setShareStatus(items.size() >= 2 ? SHARE_STATUS_SHARED
                : (items.size() == 1 ? SHARE_STATUS_EXCLUSIVE : SHARE_STATUS_FREE));
        return vo;
    }
}
