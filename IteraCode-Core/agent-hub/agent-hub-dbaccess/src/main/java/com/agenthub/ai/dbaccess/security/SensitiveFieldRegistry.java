package com.agenthub.ai.dbaccess.security;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 敏感字段清单登记与脱敏钩子（api-contract SensitiveFieldRegistry）。
 *
 * <p>清单来源：①模块配置 agenthub.dbaccess.sensitive-rules（全局/按 dsId）；②运行期 registerSensitive 登记。
 * 脱敏规则判定主判据与拒绝决策归 module-006 sql-validate-guard 消费；本模块提供登记与执行支撑。</p>
 */
@Component
public class SensitiveFieldRegistry {

    private static final String GLOBAL_KEY = "";

    private final DbAccessProperties properties;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SensitiveFieldRule>> registeredRules =
            new ConcurrentHashMap<>();

    public SensitiveFieldRegistry(DbAccessProperties properties) {
        this.properties = properties;
        registeredRules.put(GLOBAL_KEY, new CopyOnWriteArrayList<>());
    }

    /**
     * 登记全局敏感字段规则（对所有数据源生效）。
     */
    public void registerSensitive(String table, String column, SensitiveLevel level) {
        registerSensitive(null, table, column, level);
    }

    /**
     * 登记指定数据源敏感字段规则（dsId 为空时按全局登记）。
     */
    public void registerSensitive(String dsId, String table, String column, SensitiveLevel level) {
        SensitiveFieldRule rule = SensitiveFieldRule.builder()
                .dsId(dsId == null || dsId.isBlank() ? null : dsId)
                .table(table)
                .column(column)
                .level(level == null ? SensitiveLevel.HIGH : level)
                .build();
        String key = rule.getDsId() == null ? GLOBAL_KEY : rule.getDsId();
        CopyOnWriteArrayList<SensitiveFieldRule> list =
                registeredRules.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.removeIf(r -> r.getTable().equalsIgnoreCase(rule.getTable())
                && r.getColumn().equalsIgnoreCase(rule.getColumn()));
        list.add(rule);
    }

    /**
     * 按数据源加载生效的敏感字段清单（配置规则 + 运行期登记，全局 + 指定 ds 合并）。
     *
     * <p>配置来源（yml/JSON）可能漏配 level（null）。此处统一归一为 {@link SensitiveLevel#HIGH}
     * （fail-closed，与 {@link #registerSensitive} 行为一致），避免配置漏配导致 DENY/MASK 消费点静默跳过。
     * 为不污染共享配置对象，归一化通过副本完成。</p>
     */
    public List<SensitiveFieldRule> loadByDs(String dsId) {
        List<SensitiveFieldRule> result = new ArrayList<>();
        if (properties.getSensitiveRules() != null) {
            for (SensitiveFieldRule rule : properties.getSensitiveRules()) {
                if (rule.getDsId() == null || rule.getDsId().isBlank() || rule.getDsId().equals(dsId)) {
                    result.add(normalizeLevel(rule));
                }
            }
        }
        CopyOnWriteArrayList<SensitiveFieldRule> global = registeredRules.get(GLOBAL_KEY);
        if (global != null) {
            result.addAll(global);
        }
        CopyOnWriteArrayList<SensitiveFieldRule> byDs = registeredRules.get(dsId == null ? GLOBAL_KEY : dsId);
        if (byDs != null) {
            result.addAll(byDs);
        }
        return result;
    }

    /**
     * 规则归一：level 为 null（yml/JSON 漏配）按 HIGH 生成副本；否则返回原对象。
     */
    private SensitiveFieldRule normalizeLevel(SensitiveFieldRule rule) {
        if (rule.getLevel() != null) {
            return rule;
        }
        return SensitiveFieldRule.builder()
                .dsId(rule.getDsId())
                .table(rule.getTable())
                .column(rule.getColumn())
                .level(SensitiveLevel.HIGH)
                .build();
    }

    /**
     * 脱敏钩子（由查询执行在 MASK 策略下调用）。
     */
    public String mask(String value, SensitiveLevel level) {
        return SensitiveMasker.mask(value, level);
    }
}
