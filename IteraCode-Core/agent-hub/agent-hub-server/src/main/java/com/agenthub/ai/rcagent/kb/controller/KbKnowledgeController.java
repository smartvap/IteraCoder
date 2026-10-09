package com.agenthub.ai.rcagent.kb.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.kb.dto.KbBizLinkSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbColumnSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbConceptSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbDeprecateDTO;
import com.agenthub.ai.rcagent.kb.dto.KbDetailDTO;
import com.agenthub.ai.rcagent.kb.dto.KbMetricSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaBatchDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTableSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateSaveDTO;
import com.agenthub.ai.rcagent.kb.service.KbKnowledgeService;
import com.agenthub.ai.rcagent.kb.vo.KbDetailVO;
import com.agenthub.ai.rcagent.kb.vo.KbSaveResultVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaBatchVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识对象存储 API（接口1-10）：表/字段/关系/概念/指标/SQL模板/业务链路保存、
 * Schema 批量同步、软删除与详情查询。
 */
@Tag(name = "KbKnowledgeController", description = "知识库A-知识对象存储（save/deprecate/detail）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb/knowledge")
public class KbKnowledgeController {

    private final KbKnowledgeService knowledgeService;

    public KbKnowledgeController(KbKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Operation(summary = "保存表知识")
    @PostMapping("/table/save")
    public BaseResponse<KbSaveResultVO> saveTable(@RequestBody KbTableSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveTable(dto));
    }

    @Operation(summary = "保存字段知识")
    @PostMapping("/column/save")
    public BaseResponse<KbSaveResultVO> saveColumn(@RequestBody KbColumnSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveColumn(dto));
    }

    @Operation(summary = "保存表关系")
    @PostMapping("/relation/save")
    public BaseResponse<KbSaveResultVO> saveRelation(@RequestBody KbRelationSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveRelation(dto));
    }

    @Operation(summary = "保存业务概念")
    @PostMapping("/concept/save")
    public BaseResponse<KbSaveResultVO> saveConcept(@RequestBody KbConceptSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveConcept(dto));
    }

    @Operation(summary = "保存指标定义")
    @PostMapping("/metric/save")
    public BaseResponse<KbSaveResultVO> saveMetric(@RequestBody KbMetricSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveMetric(dto));
    }

    @Operation(summary = "保存SQL模板")
    @PostMapping("/template/save")
    public BaseResponse<KbSaveResultVO> saveTemplate(@RequestBody KbTemplateSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveTemplate(dto));
    }

    @Operation(summary = "保存业务链路")
    @PostMapping("/biz-link/save")
    public BaseResponse<KbSaveResultVO> saveBizLink(@RequestBody KbBizLinkSaveDTO dto) {
        return ResultUtils.success(knowledgeService.saveBizLink(dto));
    }

    @Operation(summary = "Schema 批量同步")
    @PostMapping("/schema/batch")
    public BaseResponse<KbSchemaBatchVO> schemaBatch(@RequestBody KbSchemaBatchDTO dto) {
        return ResultUtils.success(knowledgeService.schemaBatch(dto));
    }

    @Operation(summary = "软删除知识")
    @PostMapping("/deprecate")
    public BaseResponse<KbSaveResultVO> deprecate(@RequestBody KbDeprecateDTO dto) {
        return ResultUtils.success(knowledgeService.deprecate(dto));
    }

    @Operation(summary = "查询知识详情")
    @PostMapping("/detail")
    public BaseResponse<KbDetailVO> detail(@RequestBody KbDetailDTO dto) {
        return ResultUtils.success(knowledgeService.detail(dto));
    }
}
