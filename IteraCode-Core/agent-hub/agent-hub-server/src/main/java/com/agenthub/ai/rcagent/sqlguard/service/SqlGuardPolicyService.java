package com.agenthub.ai.rcagent.sqlguard.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.sqlguard.config.SqlGuardProperties;
import com.agenthub.ai.rcagent.sqlguard.constant.PolicyScope;
import com.agenthub.ai.rcagent.sqlguard.constant.SelectStarPolicy;
import com.agenthub.ai.rcagent.sqlguard.constant.SensitivePolicy;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardErrorCode;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardPolicy;
import com.agenthub.ai.rcagent.sqlguard.mapper.SqlGuardPolicyMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 安全策略服务（FR-006）：GLOBAL 默认 + DS 覆盖，save/list，config_version 热加载缓存失效。
 *
 * <p>解析规则：DS 覆盖行优先（保存时已继承 GLOBAL 默认），其次 GLOBAL 行，
 * 均缺失时使用 SqlGuardProperties.fallback 安全兜底（代码不硬编码 1000/10 阈值）。</p>
 */
@Slf4j
@Service
public class SqlGuardPolicyService {

    private final SqlGuardPolicyMapper policyMapper;
    private final SqlGuardProperties properties;
    /** 缓存：key=GLOBAL 或 DS:{dsId}，value=策略行（含 configVersion） */
    private final ConcurrentMap<String, SqlGuardPolicy> cache = new ConcurrentHashMap<>();

    public SqlGuardPolicyService(SqlGuardPolicyMapper policyMapper, SqlGuardProperties properties) {
        this.policyMapper = policyMapper;
        this.properties = properties;
    }

    /**
     * upsert 保存策略（GLOBAL/DS），成功后 config_version+1 并使缓存失效。
     *
     * @return 保存后策略行（含新版本号）
     */
    public SqlGuardPolicy save(SqlGuardPolicy policy) {
        validateForSave(policy);
        PolicyScope scope = PolicyScope.parse(policy.getPolicyScope());
        if (scope == PolicyScope.DS) {
            if (policy.getDsId() == null || policy.getDsId().isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "DS 覆盖策略必须指定 ds_id");
            }
            if (!policy.getDsId().matches("^[a-z0-9][a-z0-9-_]*$")) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 格式非法");
            }
        }

        // GLOBAL 行 ds_id 统一为空
        String global = null;
        String lookupDs = scope == PolicyScope.GLOBAL ? global : policy.getDsId();
        SqlGuardPolicy existing = policyMapper.selectByScopeAndDs(scope.name(), lookupDs);

        Date now = new Date();
        if (existing == null) {
            SqlGuardPolicy insert = new SqlGuardPolicy();
            insert.setPolicyScope(scope.name());
            insert.setDsId(scope == PolicyScope.GLOBAL ? null : policy.getDsId());
            insert.setConfigVersion(1);
            applyEditableFields(insert, policy);
            if (insert.getPolicyName() == null || insert.getPolicyName().isBlank()) {
                insert.setPolicyName(scope == PolicyScope.GLOBAL ? "GLOBAL-DEFAULT" : "DS-" + policy.getDsId());
            }
            insert.setCreateTime(now);
            insert.setUpdateTime(now);
            insert.setIsDeleted(0);
            policyMapper.insert(insert);
            invalidate(scope.name(), insert.getDsId());
            return insert;
        }

        // 覆盖已有行：仅覆盖传入的非空可编辑字段，version+1
        applyEditableFields(existing, policy);
        existing.setConfigVersion(existing.getConfigVersion() == null ? 2 : existing.getConfigVersion() + 1);
        existing.setUpdateTime(now);
        policyMapper.updateById(existing);
        invalidate(scope.name(), existing.getDsId());
        return existing;
    }

    /**
     * 查询策略分页（不含已删除），MP PaginationInnerInterceptor 拦截。
     */
    public PageResult list(String policyScope, String dsId, int page, int pageSize) {
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<SqlGuardPolicy> p = policyMapper.selectPagePolicies(
                new Page<>(Math.max(page, 1), Math.max(1, Math.min(pageSize, 100))),
                policyScope, dsId);
        return new PageResult(p.getTotal(), p.getRecords());
    }

    /**
     * 运行时解析生效策略（DS → GLOBAL → fallback），带缓存。
     */
    public SqlGuardPolicy resolveEffective(String dsId) {
        if (dsId == null || dsId.isBlank()) {
            return fallbackPolicy(null);
        }
        SqlGuardPolicy dsPolicy = getCached("DS:" + dsId, PolicyScope.DS, dsId);
        if (dsPolicy != null) {
            return dsPolicy;
        }
        SqlGuardPolicy globalPolicy = getCached("GLOBAL", PolicyScope.GLOBAL, null);
        if (globalPolicy != null) {
            return globalPolicy;
        }
        return fallbackPolicy(dsId);
    }

    private SqlGuardPolicy getCached(String key, PolicyScope scope, String dsId) {
        SqlGuardPolicy cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        SqlGuardPolicy loaded = policyMapper.selectByScopeAndDs(scope.name(), dsId);
        if (loaded == null) {
            return null;
        }
        cache.put(key, loaded);
        return loaded;
    }

    private void invalidate(String scope, String dsId) {
        String key = PolicyScope.parse(scope) == PolicyScope.GLOBAL ? "GLOBAL" : "DS:" + dsId;
        cache.remove(key);
    }

    /**
     * 策略缺失兜底（从配置读，不硬编码）。
     */
    private SqlGuardPolicy fallbackPolicy(String dsId) {
        SqlGuardPolicy p = new SqlGuardPolicy();
        p.setPolicyScope(PolicyScope.GLOBAL.name());
        p.setDsId(dsId);
        p.setPolicyName("FALLBACK-DEFAULT");
        Integer rows = properties.getFallback().getMaxRows();
        Integer seconds = properties.getFallback().getMaxSeconds();
        p.setMaxRowsDefault(rows == null ? 1000 : rows);
        p.setMaxSecondsDefault(seconds == null ? 10 : seconds);
        String sp = properties.getFallback().getSelectStarPolicy();
        p.setSelectStarPolicy(SelectStarPolicy.parse(sp) == null ? "BLOCK" : sp);
        String sen = properties.getFallback().getSensitivePolicy();
        p.setSensitivePolicy(SensitivePolicy.parse(sen) == null ? "DENY" : sen);
        p.setSensitiveSource("REGISTRY");
        p.setBlockEnabled(1);
        p.setLowAutoEnabled(1);
        p.setMvpReviewAll(0);
        p.setHighReviewRequired(1);
        p.setRepairMaxRounds(3);
        p.setConfigVersion(0);
        return p;
    }

    /** 保存前字段校验（手动 if，项目未启用 @Valid） */
    private void validateForSave(SqlGuardPolicy policy) {
        if (policy == null || policy.getPolicyScope() == null || policy.getPolicyScope().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "policy_scope 不能为空");
        }
        if (PolicyScope.parse(policy.getPolicyScope()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "policy_scope 非法");
        }
        if (policy.getMaxRowsDefault() != null
                && (policy.getMaxRowsDefault() < 1 || policy.getMaxRowsDefault() > 50000)) {
            throw new BusinessException(SqlGuardErrorCode.POLICY_ILLEGAL, "策略配置非法: max_rows_default 超限");
        }
        if (policy.getMaxSecondsDefault() != null
                && (policy.getMaxSecondsDefault() < 1 || policy.getMaxSecondsDefault() > 60)) {
            throw new BusinessException(SqlGuardErrorCode.POLICY_ILLEGAL, "策略配置非法: max_seconds_default 超限");
        }
        if (policy.getRepairMaxRounds() != null
                && (policy.getRepairMaxRounds() < 1 || policy.getRepairMaxRounds() > 10)) {
            throw new BusinessException(SqlGuardErrorCode.POLICY_ILLEGAL, "策略配置非法: repair_max_rounds 超限");
        }
        if (policy.getSelectStarPolicy() != null && SelectStarPolicy.parse(policy.getSelectStarPolicy()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "select_star_policy 非法");
        }
        if (policy.getSensitivePolicy() != null && SensitivePolicy.parse(policy.getSensitivePolicy()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sensitive_policy 非法");
        }
    }

    /** 覆盖可编辑字段：仅处理非空（null 表示不更新） */
    private void applyEditableFields(SqlGuardPolicy target, SqlGuardPolicy src) {
        if (src.getPolicyName() != null) {
            target.setPolicyName(src.getPolicyName());
        }
        if (src.getMaxRowsDefault() != null) {
            target.setMaxRowsDefault(src.getMaxRowsDefault());
        }
        if (src.getMaxSecondsDefault() != null) {
            target.setMaxSecondsDefault(src.getMaxSecondsDefault());
        }
        if (src.getSelectStarPolicy() != null) {
            target.setSelectStarPolicy(src.getSelectStarPolicy());
        }
        if (src.getBlockEnabled() != null) {
            target.setBlockEnabled(src.getBlockEnabled());
        }
        if (src.getSensitivePolicy() != null) {
            target.setSensitivePolicy(src.getSensitivePolicy());
        }
        if (src.getSensitiveSource() != null) {
            target.setSensitiveSource(src.getSensitiveSource());
        }
        if (src.getLowAutoEnabled() != null) {
            target.setLowAutoEnabled(src.getLowAutoEnabled());
        }
        if (src.getMvpReviewAll() != null) {
            target.setMvpReviewAll(src.getMvpReviewAll());
        }
        if (src.getHighReviewRequired() != null) {
            target.setHighReviewRequired(src.getHighReviewRequired());
        }
        if (src.getRepairMaxRounds() != null) {
            target.setRepairMaxRounds(src.getRepairMaxRounds());
        }
        if (src.getDialectWhitelist() != null) {
            target.setDialectWhitelist(src.getDialectWhitelist());
        }
        if (src.getRemark() != null) {
            target.setRemark(src.getRemark());
        }
    }

    /** 方言白名单 JSON 文本序列化辅助 */
    public static String whitelistToJson(List<String> whitelist) {
        return whitelist == null ? null : JSON.toJSONString(whitelist);
    }

    /** 方言白名单 JSON 反序列化辅助 */
    public static List<String> whitelistFromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }
}
