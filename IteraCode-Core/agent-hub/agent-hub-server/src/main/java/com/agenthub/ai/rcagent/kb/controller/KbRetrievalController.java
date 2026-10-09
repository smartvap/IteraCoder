package com.agenthub.ai.rcagent.kb.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.kb.dto.KbBizLinkRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSemanticRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateRetrievalDTO;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbBizLinkRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbSemanticRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbTemplateRetrievalVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识检索召回 API（接口20-24）：五路只读召回。
 */
@Tag(name = "KbRetrievalController", description = "知识库A-检索召回（schema/template/biz-link/relation/semantic）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb/retrieval")
public class KbRetrievalController {

    private final KbRetrievalService retrievalService;

    public KbRetrievalController(KbRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Operation(summary = "Schema 检索召回")
    @PostMapping("/schema")
    public BaseResponse<KbSchemaRetrievalVO> retrieveSchema(@RequestBody KbSchemaRetrievalDTO dto) {
        return ResultUtils.success(retrievalService.retrieveSchema(dto));
    }

    @Operation(summary = "SQL模板检索召回")
    @PostMapping("/template")
    public BaseResponse<KbTemplateRetrievalVO> retrieveTemplate(@RequestBody KbTemplateRetrievalDTO dto) {
        return ResultUtils.success(retrievalService.retrieveTemplate(dto));
    }

    @Operation(summary = "业务链路检索召回")
    @PostMapping("/biz-link")
    public BaseResponse<KbBizLinkRetrievalVO> retrieveBizLink(@RequestBody KbBizLinkRetrievalDTO dto) {
        return ResultUtils.success(retrievalService.retrieveBizLink(dto));
    }

    @Operation(summary = "表关系检索召回")
    @PostMapping("/relation")
    public BaseResponse<KbRelationRetrievalVO> retrieveRelation(@RequestBody KbRelationRetrievalDTO dto) {
        return ResultUtils.success(retrievalService.retrieveRelation(dto));
    }

    @Operation(summary = "语义知识检索召回")
    @PostMapping("/semantic")
    public BaseResponse<KbSemanticRetrievalVO> retrieveSemantic(@RequestBody KbSemanticRetrievalDTO dto) {
        return ResultUtils.success(retrievalService.retrieveSemantic(dto));
    }
}
