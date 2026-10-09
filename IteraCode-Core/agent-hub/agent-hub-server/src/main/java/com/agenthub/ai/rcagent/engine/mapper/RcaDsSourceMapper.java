package com.agenthub.ai.rcagent.engine.mapper;

import com.agenthub.ai.rcagent.engine.vo.RcaSourceCandidateVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RCAgent 候选数据源只读 Mapper（零 DDL，纯 SELECT）。
 *
 * <p>位于 {@code com.agenthub.ai.rcagent.engine.mapper} 包，已被既有
 * {@code RcaMybatisConfig} 的 {@code @MapperScan} 覆盖（不得修改该配置）；
 * XML 与 {@code RcaWorkflowMetadataMapper.xml} 同目录同风格。</p>
 *
 * <p>候选源判定口径（data-model.md §2.1/§4.2）：{@code t_ds_config cfg} INNER JOIN
 * {@code t_ds_config_admin adm}，双表 {@code is_deleted=0} 且 {@code cfg.status=1}
 * 且 {@code adm.manage_state='ACTIVE'}；仅取非敏感字段
 * （ds_id/ds_name/system_code/db_type/manage_state），禁止 {@code SELECT *} 与字符串拼接。</p>
 */
@Mapper
public interface RcaDsSourceMapper {

    /** 按系统编码展开该 system_code 下的全部 ACTIVE 候选源（按 ds_id 升序） */
    List<RcaSourceCandidateVO> selectActiveCandidatesBySystemCode(@Param("systemCode") String systemCode);

    /** 按显式 ds_id 集合查询 ACTIVE 候选源（IN 用 foreach + #{} 参数化，按 ds_id 升序） */
    List<RcaSourceCandidateVO> selectActiveCandidatesByDsIds(@Param("dsIds") List<String> dsIds);

    /** 单个 ds_id 精确查询 ACTIVE 候选源（不存在/非 ACTIVE 返回 null） */
    RcaSourceCandidateVO selectActiveCandidateByDsId(@Param("dsId") String dsId);

    /** 全部 ACTIVE 候选源（含 system_code 为空者），供服务层按 system_code 内存分组 */
    List<RcaSourceCandidateVO> selectActiveSystemGroups();
}
