package com.agenthub.ai.dbaccess.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WhitelistRule 白名单语义测试（默认放行 + 三种 mode + 通配符）。
 */
class WhitelistRuleTest {

    @Test
    @DisplayName("VALID_MODES 含 ALL/ALLOW_ONLY/DENY_ONLY 三种 mode")
    void should_containValidModes() {
        assertThat(WhitelistRule.VALID_MODES).containsExactlyInAnyOrder(
                WhitelistRule.MODE_ALL,
                WhitelistRule.MODE_ALLOW_ONLY,
                WhitelistRule.MODE_DENY_ONLY);
    }

    @Test
    @DisplayName("mode 缺省(null/blank)且 tables 空 → 默认允许全部表（default allow）")
    void should_allowAll_when_modeMissingAndTablesEmpty() {
        assertThat(WhitelistRule.builder().build().allowTable("CUSTOMER")).isTrue();
        WhitelistRule noModeEmpty = WhitelistRule.builder().tables(new ArrayList<>()).build();
        assertThat(noModeEmpty.allowTable("ANY_TABLE")).isTrue();
        WhitelistRule blankMode = WhitelistRule.builder().tables(new ArrayList<>()).mode("  ").build();
        assertThat(blankMode.allowTable("ANY_TABLE")).isTrue();
    }

    @Test
    @DisplayName("mode 缺省但 tables 非空 → 按 ALLOW_ONLY 兼容旧数据")
    void should_actAsAllowOnly_when_modeMissingButTablesNotEmpty() {
        WhitelistRule rule = WhitelistRule.builder().tables(List.of("CUSTOMER", "ORDERS")).build();
        assertThat(rule.allowTable("customer")).isTrue();
        assertThat(rule.allowTable("INVOICE")).isFalse();
    }

    @Test
    @DisplayName("显式空白名单（mode=ALLOW_ONLY + 空 tables）仍拒绝全部表（保留 fail-closed 能力）")
    void should_rejectAll_when_explicitEmptyAllowOnly() {
        WhitelistRule empty = WhitelistRule.empty();
        assertThat(empty.getMode()).isEqualTo(WhitelistRule.MODE_ALLOW_ONLY);
        assertThat(empty.allowTable("CUSTOMER")).isFalse();
        WhitelistRule explicit = WhitelistRule.builder()
                .tables(List.of())
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(explicit.allowTable("CUSTOMER")).isFalse();
    }

    @Test
    @DisplayName("isExplicitRejectAll：仅显式 ALLOW_ONLY+空 tables 返回 true，其余 false")
    void should_detectExplicitRejectAll() {
        // 缺省 mode + null tables（默认 ALL 全放）→ 非显式全拒
        assertThat(WhitelistRule.builder().build().isExplicitRejectAll()).isFalse();
        // 缺省 mode + 空 tables → 非显式全拒（默认全放）
        assertThat(WhitelistRule.builder().tables(new ArrayList<>()).build().isExplicitRejectAll()).isFalse();
        // 缺省 mode + 非空 tables → 非显式全拒（allowTable 按 ALLOW_ONLY 兼容旧数据，配置了清单 BR-001 应满足）
        WhitelistRule legacy = WhitelistRule.builder().tables(List.of("CUSTOMER")).build();
        assertThat(legacy.isExplicitRejectAll()).isFalse();
        assertThat(legacy.allowTable("CUSTOMER")).isTrue();
        // 显式 ALLOW_ONLY + 空清单 → 显式全拒
        assertThat(WhitelistRule.builder().tables(List.of()).mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build().isExplicitRejectAll()).isTrue();
        // 显式 ALLOW_ONLY + null tables → 显式全拒（防御）
        assertThat(WhitelistRule.builder().mode(WhitelistRule.MODE_ALLOW_ONLY).build().isExplicitRejectAll()).isTrue();
        // 显式 ALLOW_ONLY + 非空清单 → false
        WhitelistRule allow = WhitelistRule.builder().tables(List.of("A")).mode(WhitelistRule.MODE_ALLOW_ONLY).build();
        assertThat(allow.isExplicitRejectAll()).isFalse();
        // DENY_ONLY + 空清单 → false（黑名单空 = 全部放行）
        assertThat(WhitelistRule.builder().tables(List.of()).mode(WhitelistRule.MODE_DENY_ONLY)
                .build().isExplicitRejectAll()).isFalse();
        // ALL → false
        assertThat(WhitelistRule.builder().mode(WhitelistRule.MODE_ALL).build().isExplicitRejectAll()).isFalse();
        // empty()（显式空白名单）→ true
        assertThat(WhitelistRule.empty().isExplicitRejectAll()).isTrue();
        // mode 带空白 trim 后仍识别（防御）
        assertThat(WhitelistRule.builder().tables(new ArrayList<>()).mode("  ALLOW_ONLY  ")
                .build().isExplicitRejectAll()).isTrue();
    }

    @Test
    @DisplayName("表名规范化：剥反引号/双引号并转大写")
    void should_normalize_when_backtickOrQuote() {
        assertThat(WhitelistRule.normalizeTable("`customer`")).isEqualTo("CUSTOMER");
        assertThat(WhitelistRule.normalizeTable("\"orders\"")).isEqualTo("ORDERS");
        assertThat(WhitelistRule.normalizeTable("  customer  ")).isEqualTo("CUSTOMER");
        assertThat(WhitelistRule.normalizeTable(null)).isEmpty();
    }

    @Test
    @DisplayName("表名规范化：剥方括号（SQL Server）与三种引号统一口径，不成对引号保留")
    void should_normalize_when_squareBracketOrMixedQuotes() {
        // SQL Server 方括号包裹
        assertThat(WhitelistRule.normalizeTable("[T]")).isEqualTo("T");
        assertThat(WhitelistRule.normalizeTable("[customer]")).isEqualTo("CUSTOMER");
        assertThat(WhitelistRule.normalizeTable("  [Orders]  ")).isEqualTo("ORDERS");
        // 三种引号口径一致
        assertThat(WhitelistRule.normalizeTable("[ORDERS]")).isEqualTo(WhitelistRule.normalizeTable("`ORDERS`"));
        assertThat(WhitelistRule.normalizeTable("[ORDERS]")).isEqualTo(WhitelistRule.normalizeTable("\"ORDERS\""));
        // 混合引号不成对：仅剥离成对引号，避免误伤以引号字符开头/结尾的合法名
        assertThat(WhitelistRule.normalizeTable("[T\"")).isEqualTo("[T\"");
        assertThat(WhitelistRule.normalizeTable("\"T]")).isEqualTo("\"T]");
        // 单字符不构成引号包裹
        assertThat(WhitelistRule.normalizeTable("[")).isEqualTo("[");
        assertThat(WhitelistRule.normalizeTable("]")).isEqualTo("]");
    }

    @Test
    @DisplayName("白名单命中：方括号/[DB].[T] 分段归一与反引号口径一致")
    void should_allow_when_squareBracketQuotedTable() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER", "ORDERS"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.allowTable("[CUSTOMER]")).isTrue();
        assertThat(rule.allowTable("[ORDERS]")).isTrue();
        assertThat(rule.allowTable("[INVOICE]")).isFalse();
        // DENY_ONLY 方括号规则同样命中
        WhitelistRule deny = WhitelistRule.builder()
                .tables(List.of("[SECRET_LOG]"))
                .mode(WhitelistRule.MODE_DENY_ONLY)
                .build();
        assertThat(deny.allowTable("SECRET_LOG")).isFalse();
        assertThat(deny.allowTable("[secret_log]")).isFalse();
    }

    @Test
    @DisplayName("ALLOW_ONLY 白名单表命中（大小写不敏感）")
    void should_allow_when_tableInList() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER", "ORDERS"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.allowTable("customer")).isTrue();
        assertThat(rule.allowTable("`ORDERS`")).isTrue();
        assertThat(rule.allowTable("INVOICE")).isFalse();
    }

    @Test
    @DisplayName("mode=ALL 时任意表放行（忽略 tables/columns）")
    void should_allowAll_when_modeAll() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER"))
                .columns(Map.of("CUSTOMER", List.of("ID")))
                .mode(WhitelistRule.MODE_ALL)
                .build();
        assertThat(rule.allowTable("WHATEVER")).isTrue();
        assertThat(rule.allowTable("")).isTrue();
    }

    @Test
    @DisplayName("DENY_ONLY 精确黑名单：命中拒绝、未命中放行；tables 空 → 全部放行")
    void should_denyOnly_when_exactTable() {
        WhitelistRule deny = WhitelistRule.builder()
                .tables(List.of("SECRET_LOG"))
                .mode(WhitelistRule.MODE_DENY_ONLY)
                .build();
        assertThat(deny.allowTable("secret_log")).isFalse();
        assertThat(deny.allowTable("`SECRET_LOG`")).isFalse();
        assertThat(deny.allowTable("CUSTOMER")).isTrue();
        WhitelistRule denyEmpty = WhitelistRule.builder()
                .tables(List.of())
                .mode(WhitelistRule.MODE_DENY_ONLY)
                .build();
        assertThat(denyEmpty.allowTable("CUSTOMER")).isTrue();
    }

    @Test
    @DisplayName("通配符 * 匹配任意字符序列（ALLOW_ONLY 前缀/后缀规则）")
    void should_match_when_wildcardStar() {
        WhitelistRule prefix = WhitelistRule.builder()
                .tables(List.of("CRM_*"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(prefix.allowTable("CRM_USER")).isTrue();
        assertThat(prefix.allowTable("CRM_ORDER")).isTrue();
        assertThat(prefix.allowTable("OA_ORDER")).isFalse();

        WhitelistRule suffix = WhitelistRule.builder()
                .tables(List.of("*_LOG"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(suffix.allowTable("AUDIT_LOG")).isTrue();
        assertThat(suffix.allowTable("LOG_AUDIT")).isFalse();
    }

    @Test
    @DisplayName("通配符 % 与 * 等价")
    void should_match_when_wildcardPercent() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CRM_%", "%_LOG"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.allowTable("CRM_USER")).isTrue();
        assertThat(rule.allowTable("AUDIT_LOG")).isTrue();
        assertThat(rule.allowTable("OA_ORDER")).isFalse();
    }

    @Test
    @DisplayName("通配匹配大小写不敏感且规则小写也可命中")
    void should_match_when_wildcardCaseInsensitive() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("crm_*"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.allowTable("CRM_USER")).isTrue();
    }

    @Test
    @DisplayName("DENY_ONLY 通配黑名单：命中拒绝、未命中放行")
    void should_denyOnly_when_wildcardTable() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("*_TMP", "ARCHIVE_*"))
                .mode(WhitelistRule.MODE_DENY_ONLY)
                .build();
        assertThat(rule.allowTable("SESSION_TMP")).isFalse();
        assertThat(rule.allowTable("ARCHIVE_2024")).isFalse();
        assertThat(rule.allowTable("CUSTOMER")).isTrue();
    }

    @Test
    @DisplayName("表级放行但表不在白名单时列级判定也拒绝")
    void should_denyColumn_when_tableNotAllowed() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER"))
                .columns(Map.of("CUSTOMER", List.of("ID", "NAME")))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.allowColumn("OTHER", "ID")).isFalse();
    }

    @Test
    @DisplayName("columns 为空的表视为整表白名单放行")
    void should_allowAnyColumn_when_noColumnRestriction() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER"))
                .build();
        assertThat(rule.allowColumn("CUSTOMER", "SECRET")).isTrue();
        assertThat(rule.hasColumnRestriction("CUSTOMER")).isFalse();
    }

    @Test
    @DisplayName("列级限制命中放行、未列出列拒绝（精确授权表）")
    void should_checkColumn_when_columnRestricted() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER"))
                .columns(Map.of("CUSTOMER", List.of("ID", "NAME")))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.hasColumnRestriction("customer")).isTrue();
        assertThat(rule.allowColumn("CUSTOMER", "name")).isTrue();
        assertThat(rule.allowColumn("CUSTOMER", "PHONE")).isFalse();
        // 列限制表仍要求表级放行
        assertThat(rule.allowColumn("INVOICE", "ID")).isFalse();
    }

    @Test
    @DisplayName("columns key 大小写不敏感（精确列限制 normalize 后命中）")
    void should_checkColumn_when_lowerCaseColumnsKey() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUSTOMER"))
                .columns(Map.of("customer", List.of("ID")))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.hasColumnRestriction("CUSTOMER")).isTrue();
        assertThat(rule.allowColumn("CUSTOMER", "ID")).isTrue();
        assertThat(rule.allowColumn("CUSTOMER", "PHONE")).isFalse();
    }

    @Test
    @DisplayName("通配授权表（tables 含 * ）不启用列级限制，allowColumn 不拦截")
    void should_notRestrictColumn_when_wildcardTable() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("CUST*"))
                .columns(Map.of("CUSTOMER", List.of("ID")))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        assertThat(rule.allowTable("CUSTOMER")).isTrue();
        assertThat(rule.hasColumnRestriction("CUSTOMER")).isFalse();
        assertThat(rule.allowColumn("CUSTOMER", "PHONE")).isTrue();
    }

    @Test
    @DisplayName("DENY_ONLY 模式不启用列级限制，allowColumn 直接放行")
    void should_notRestrictColumn_when_denyOnly() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("LOG"))
                .columns(Map.of("LOG", List.of("ID")))
                .mode(WhitelistRule.MODE_DENY_ONLY)
                .build();
        assertThat(rule.hasColumnRestriction("LOG")).isFalse();
        assertThat(rule.allowColumn("LOG", "ANY")).isTrue();
    }

    @Test
    @DisplayName("ALL 模式不启用列级限制，allowColumn 直接放行")
    void should_notRestrictColumn_when_allMode() {
        WhitelistRule rule = WhitelistRule.builder()
                .tables(List.of("LOG"))
                .columns(Map.of("LOG", List.of("ID")))
                .mode(WhitelistRule.MODE_ALL)
                .build();
        assertThat(rule.hasColumnRestriction("LOG")).isFalse();
        assertThat(rule.allowColumn("LOG", "ANY")).isTrue();
    }

    @Test
    @DisplayName("JSON 双向解析 fromJson/toJson")
    void should_roundTrip_when_json() {
        String json = "{\"tables\":[\"CUSTOMER\"],\"columns\":{\"CUSTOMER\":[\"ID\"]},\"mode\":\"ALLOW_ONLY\"}";
        WhitelistRule rule = WhitelistRule.fromJson(json);
        assertThat(rule).isNotNull();
        assertThat(rule.getTables()).containsExactly("CUSTOMER");
        assertThat(rule.allowTable("customer")).isTrue();
        assertThat(rule.allowColumn("CUSTOMER", "ID")).isTrue();
        assertThat(rule.toJson()).contains("CUSTOMER");
    }

    @Test
    @DisplayName("空或空白 JSON 返回 null")
    void should_returnNull_when_jsonBlank() {
        assertThat(WhitelistRule.fromJson(null)).isNull();
        assertThat(WhitelistRule.fromJson("  ")).isNull();
    }
}
