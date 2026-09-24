package com.agenthub.ai.datasource.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.datasource.constant.DsErrorCode;
import com.agenthub.ai.datasource.dto.DsConfigPageQueryDTO;
import com.agenthub.ai.datasource.dto.DsConfigSaveDTO;
import com.agenthub.ai.datasource.dto.DsTestConnectionRequest;
import com.agenthub.ai.datasource.dto.SpaceBindRequest;
import com.agenthub.ai.datasource.exception.DsHealthCheckFailedException;
import com.agenthub.ai.datasource.exception.DsSpaceLinkUnavailableException;
import com.agenthub.ai.datasource.service.DsAuditService;
import com.agenthub.ai.datasource.service.DsConfigManageService;
import com.agenthub.ai.datasource.service.DsHealthCheckService;
import com.agenthub.ai.datasource.service.DsMetaService;
import com.agenthub.ai.datasource.service.DsOpsSummaryService;
import com.agenthub.ai.datasource.service.DsSpaceLinkService;
import com.agenthub.ai.datasource.vo.DbTypeOptionVO;
import com.agenthub.ai.datasource.vo.DsAuditVO;
import com.agenthub.ai.datasource.vo.DsConfigDetailVO;
import com.agenthub.ai.datasource.vo.DsConfigVO;
import com.agenthub.ai.datasource.vo.DsStateResultVO;
import com.agenthub.ai.datasource.vo.HealthCheckResultVO;
import com.agenthub.ai.datasource.vo.OpsSummaryVO;
import com.agenthub.ai.datasource.vo.SpaceBindResultVO;
import com.agenthub.ai.datasource.vo.SpaceOptionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多数据源注册表管理 API（/api/v1/datasource/**，15 个管理接口）。
 *
 * <p>凭据脱敏贯穿（BR-002）：本控制器不记录/不回传密码明文；
 * register/update/test-connection 入参含 ${ENV} 引用或一次性密码，均不加 @Loggable（避免 AOP 参数入库泄密）。</p>
 */
@Tag(name = "DsConfigController", description = "多数据源注册表管理（CRUD/启停/健康/审计/空间联动/字典）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/datasource")
public class DsConfigController {

    private final DsConfigManageService manageService;
    private final DsHealthCheckService healthCheckService;
    private final DsSpaceLinkService spaceLinkService;
    private final DsAuditService auditService;
    private final DsMetaService metaService;
    private final DsOpsSummaryService opsSummaryService;

    public DsConfigController(DsConfigManageService manageService,
                              DsHealthCheckService healthCheckService,
                              DsSpaceLinkService spaceLinkService,
                              DsAuditService auditService,
                              DsMetaService metaService,
                              DsOpsSummaryService opsSummaryService) {
        this.manageService = manageService;
        this.healthCheckService = healthCheckService;
        this.spaceLinkService = spaceLinkService;
        this.auditService = auditService;
        this.metaService = metaService;
        this.opsSummaryService = opsSummaryService;
    }

    @Operation(summary = "注册数据源")
    @PostMapping("/register")
    public BaseResponse<DsConfigVO> register(@RequestBody DsConfigSaveDTO dto) {
        return ResultUtils.success(manageService.register(dto, currentUserId()));
    }

    @Operation(summary = "修改数据源")
    @PutMapping("/{dsId}/update")
    public BaseResponse<DsConfigVO> update(@PathVariable String dsId, @RequestBody DsConfigSaveDTO dto) {
        return ResultUtils.success(manageService.update(dsId, dto, currentUserId()));
    }

    @Operation(summary = "删除数据源（软删）")
    @DeleteMapping("/{dsId}/delete")
    public BaseResponse<DsStateResultVO> delete(@PathVariable String dsId,
                                                @RequestParam(required = false) String remark) {
        return ResultUtils.success(manageService.delete(dsId, remark, currentUserId()));
    }

    @Operation(summary = "启用数据源")
    @PostMapping("/{dsId}/enable")
    public BaseResponse<?> enable(@PathVariable String dsId,
                                  @RequestParam(required = false) Boolean force) {
        try {
            return ResultUtils.success(manageService.enable(dsId, force, currentUserId()));
        } catch (DsHealthCheckFailedException e) {
            // 健康检查未通过：返回 43005 + 诊断（data 带 HealthCheckResultVO）
            return new BaseResponse<>(DsErrorCode.HEALTH_CHECK_FAILED, e.getHealth(), e.getMessage());
        }
    }

    @Operation(summary = "停用数据源")
    @PostMapping("/{dsId}/disable")
    public BaseResponse<DsStateResultVO> disable(@PathVariable String dsId,
                                                 @RequestParam(required = false) String remark) {
        return ResultUtils.success(manageService.disable(dsId, remark, currentUserId()));
    }

    @Operation(summary = "数据源详情（脱敏）")
    @GetMapping("/{dsId}")
    public BaseResponse<DsConfigDetailVO> detail(@PathVariable String dsId) {
        return ResultUtils.success(manageService.detail(dsId));
    }

    @Operation(summary = "数据源分页列表")
    @GetMapping("/page")
    public BaseResponse<PageResult> page(DsConfigPageQueryDTO query) {
        return ResultUtils.success(manageService.page(query));
    }

    @Operation(summary = "测试连接（一次性凭据）")
    @PostMapping("/test-connection")
    public BaseResponse<HealthCheckResultVO> testConnection(@RequestBody DsTestConnectionRequest req) {
        return ResultUtils.success(healthCheckService.testConnection(req, currentUserId()));
    }

    @Operation(summary = "健康检查并留痕")
    @PostMapping("/{dsId}/health-check")
    public BaseResponse<HealthCheckResultVO> healthCheck(@PathVariable String dsId) {
        return ResultUtils.success(healthCheckService.healthCheck(dsId, currentUserId()));
    }

    @Operation(summary = "健康历史分页")
    @GetMapping("/{dsId}/health-logs")
    public BaseResponse<PageResult> healthLogs(@PathVariable String dsId,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize,
                                               @RequestParam(required = false) String checkType) {
        return ResultUtils.success(healthCheckService.healthLogs(dsId, page, pageSize, checkType));
    }

    @Operation(summary = "审计记录分页")
    @GetMapping("/{dsId}/audit-logs")
    public BaseResponse<PageResult> auditLogs(@PathVariable String dsId,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer pageSize,
                                              @RequestParam(required = false) String action) {
        return ResultUtils.success(auditService.auditLogs(dsId, page, pageSize, action));
    }

    @Operation(summary = "知识空间绑定/重试")
    @PostMapping("/{dsId}/space/bind")
    public BaseResponse<SpaceBindResultVO> spaceBind(@PathVariable String dsId,
                                                     @RequestBody SpaceBindRequest req) {
        try {
            return ResultUtils.success(spaceLinkService.bind(dsId, req, currentUserId()));
        } catch (DsSpaceLinkUnavailableException e) {
            // llm-wiki 不可用：返回 43006 + 降级结果（data 带 SpaceBindResultVO，BIND_PENDING）
            return new BaseResponse<>(DsErrorCode.SPACE_LINK_UNAVAILABLE, e.getResult(), e.getMessage());
        }
    }

    @Operation(summary = "知识空间候选列表")
    @GetMapping("/space/options")
    public BaseResponse<List<SpaceOptionVO>> spaceOptions() {
        List<SpaceOptionVO> options = spaceLinkService.spaceOptions();
        if (options.isEmpty() && !spaceLinkService.spaceServiceAvailable()) {
            return ResultUtils.success(options, "llm-wiki 空间服务不可用，可稍后绑定");
        }
        return ResultUtils.success(options);
    }

    @Operation(summary = "库类型字典")
    @GetMapping("/db-types")
    public BaseResponse<List<DbTypeOptionVO>> dbTypes() {
        return ResultUtils.success(metaService.dbTypes());
    }

    @Operation(summary = "运维概况（P2 可选）")
    @GetMapping("/{dsId}/ops-summary")
    public BaseResponse<OpsSummaryVO> opsSummary(@PathVariable String dsId) {
        return ResultUtils.success(opsSummaryService.opsSummary(dsId));
    }

    /** 当前操作人（base JWT 占位鉴权；无登录态时记 0） */
    private Long currentUserId() {
        Long id = BaseContext.getCurrentId();
        return id == null ? 0L : id;
    }
}
