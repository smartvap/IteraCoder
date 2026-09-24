package com.agenthub.ai.dbaccess.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlReadonlyValidator 只读 SQL 校验/轻量解析测试。
 */
class SqlReadonlyValidatorTest {

    private final SqlReadonlyValidator validator = new SqlReadonlyValidator();

    @Test
    @DisplayName("校验文本去除注释与尾分号（空白归一化后比对）")
    void should_stripCommentsAndSemicolon_when_validate() {
        String sql = "SELECT a, b /* block */ -- line\n FROM t WHERE x = 1;";
        String cleaned = validator.forValidation(sql).replaceAll("\\s+", " ");
        assertThat(cleaned).isEqualTo("SELECT a, b FROM t WHERE x = 1");
    }

    @Test
    @DisplayName("执行文本仅去除尾部分号，保留注释")
    void should_onlyStripSemicolon_when_execute() {
        assertThat(validator.forExecution("SELECT 1 -- note\n;")).isEqualTo("SELECT 1 -- note");
        assertThat(validator.forExecution("SELECT 1;;")).isEqualTo("SELECT 1");
        assertThat(validator.forExecution(null)).isEmpty();
    }

    @Test
    @DisplayName("首关键字识别（忽略前导注释与空白）")
    void should_firstKeyword_when_various() {
        assertThat(validator.firstKeyword("select * from t")).isEqualTo("SELECT");
        assertThat(validator.firstKeyword("  explain select 1")).isEqualTo("EXPLAIN");
        assertThat(validator.firstKeyword("DELETE FROM t")).isEqualTo("DELETE");
        assertThat(validator.firstKeyword("   ")).isEmpty();
    }

    @Test
    @DisplayName("顶层 SELECT * 探测命中，函数内 * 不命中")
    void should_detectSelectStar_when_topLevel() {
        assertThat(validator.hasTopLevelSelectStar("SELECT * FROM t")).isTrue();
        assertThat(validator.hasTopLevelSelectStar("SELECT t.* FROM t")).isTrue();
        assertThat(validator.hasTopLevelSelectStar("SELECT DISTINCT * FROM t")).isTrue();
        assertThat(validator.hasTopLevelSelectStar("SELECT count(*) FROM t")).isFalse();
        assertThat(validator.hasTopLevelSelectStar("SELECT id, name FROM t")).isFalse();
    }

    @Test
    @DisplayName("提取引用表集合（from/join、库名前缀剥离、反引号）")
    void should_extractTables_when_fromAndJoin() {
        String sql = "SELECT o.id FROM `crm_db`.`orders` o JOIN customer c ON o.cid = c.id";
        Set<String> tables = validator.referencedTables(sql);
        assertThat(tables).containsExactlyInAnyOrder("ORDERS", "CUSTOMER");
    }

    @Test
    @DisplayName("限定表名集合：保留库前缀（有前缀 DB_A.T1，无前缀纯表名 T1）")
    void should_extractQualifiedTables_when_schemaPrefix() {
        assertThat(validator.referencedQualifiedTables("SELECT * FROM DB_A.T1"))
                .containsExactly("DB_A.T1");
        assertThat(validator.referencedQualifiedTables("SELECT a.id FROM T1 a JOIN DB_B.T2 b ON a.id = b.id"))
                .containsExactlyInAnyOrder("T1", "DB_B.T2");
    }

    @Test
    @DisplayName("限定表名集合：反引号/别名/大小写归一仍得 DB_A.T1")
    void should_normalizeQualifiedTables_when_backtickOrAlias() {
        assertThat(validator.referencedQualifiedTables("SELECT * FROM `DB_A`.`T1`"))
                .containsExactly("DB_A.T1");
        assertThat(validator.referencedQualifiedTables("SELECT * FROM DB_A.T1 a"))
                .containsExactly("DB_A.T1");
        assertThat(validator.referencedQualifiedTables("SELECT * FROM db_a.t1"))
                .containsExactly("DB_A.T1");
    }

    @Test
    @DisplayName("方括号限定名（SQL Server）：[DB].[T] 归一剥离方括号，与反引号口径一致")
    void should_normalizeQualifiedTables_when_squareBracket() {
        // [DB_A].[T1] → 各段剥离方括号 → DB_A.T1（与 `DB_A`.`T1` 口径一致）
        assertThat(validator.referencedQualifiedTables("SELECT * FROM [DB_A].[T1]"))
                .containsExactly("DB_A.T1");
        assertThat(validator.referencedTables("SELECT * FROM [DB_A].[T1]"))
                .containsExactly("T1");
        assertThat(validator.referencedQualifiedTables(
                "SELECT a.id FROM [T1] a JOIN [DB_B].[T2] b ON a.id = b.id"))
                .containsExactlyInAnyOrder("T1", "DB_B.T2");
        // 与反引号形态归一结果完全相同
        assertThat(validator.referencedQualifiedTables("SELECT * FROM [DB_A].[T1]"))
                .isEqualTo(validator.referencedQualifiedTables("SELECT * FROM `DB_A`.`T1`"));
        // 投影方括号列同样归一（来源列口径一致）
        assertThat(validator.projectionColumns("SELECT [phone] FROM [T]")).containsExactly("PHONE");
    }

    @Test
    @DisplayName("限定表名集合：子查询/多表形态与纯表名集合口径一致（末段）")
    void should_consistentQualifiedAndPlain_when_subqueryMultiTable() {
        String sql = "SELECT a FROM (SELECT b FROM inner_t) sub JOIN DB_X.outer_t o ON o.id = sub.b";
        assertThat(validator.referencedQualifiedTables(sql))
                .containsExactlyInAnyOrder("INNER_T", "DB_X.OUTER_T");
        // referencedTables 语义不变：始终剥离库前缀，返回纯表名
        assertThat(validator.referencedTables(sql)).containsExactlyInAnyOrder("INNER_T", "OUTER_T");
    }

    @Test
    @DisplayName("schemaOf：解析库前缀，无前缀/非法返回 null")
    void should_schemaOf_when_qualifiedOrPlain() {
        assertThat(SqlReadonlyValidator.schemaOf("DB_A.T1")).isEqualTo("DB_A");
        assertThat(SqlReadonlyValidator.schemaOf("T1")).isNull();
        assertThat(SqlReadonlyValidator.schemaOf(".T1")).isNull();
        assertThat(SqlReadonlyValidator.schemaOf("DB_A.")).isNull();
        assertThat(SqlReadonlyValidator.schemaOf(null)).isNull();
    }

    @Test
    @DisplayName("子查询中的表一并捕获")
    void should_extractNestedTables_when_subquery() {
        String sql = "SELECT a FROM (SELECT b FROM inner_t) sub JOIN outer_t o ON o.id = sub.b";
        Set<String> tables = validator.referencedTables(sql);
        assertThat(tables).containsExactlyInAnyOrder("INNER_T", "OUTER_T");
    }

    @Test
    @DisplayName("投影列提取：裸列/别名列，忽略函数与 *")
    void should_extractColumns_when_projection() {
        assertThat(validator.projectionColumns("SELECT id, name FROM t"))
                .containsExactly("ID", "NAME");
        assertThat(validator.projectionColumns("SELECT t.phone FROM t"))
                .containsExactly("PHONE");
        assertThat(validator.projectionColumns("SELECT count(*) FROM t")).isEmpty();
        assertThat(validator.projectionColumns("SELECT * FROM t")).isEmpty();
    }

    @Test
    @DisplayName("标识符命中探测（独立词匹配，忽略大小写）")
    void should_containIdentifier_when_wordBoundary() {
        assertThat(validator.containsIdentifier("SELECT phone FROM t", "phone")).isTrue();
        assertThat(validator.containsIdentifier("SELECT PHONE FROM t", "phone")).isTrue();
        assertThat(validator.containsIdentifier("SELECT telephone FROM t", "phone")).isFalse();
        assertThat(validator.containsIdentifier("SELECT my_phone_no FROM t", "phone")).isFalse();
        assertThat(validator.containsIdentifier("SELECT id FROM t", "phone")).isFalse();
    }

    @Test
    @DisplayName("按位置投影来源列：别名列解析回真实列，函数/* 位置占 null")
    void should_positionalProjection_when_aliasOrFunction() {
        assertThat(validator.projectionColumnsByPosition("SELECT phone AS p, name FROM t"))
                .containsExactly("PHONE", "NAME");
        assertThat(validator.projectionColumnsByPosition("SELECT t.phone, count(*) FROM t"))
                .containsExactly("PHONE", null);
        assertThat(validator.projectionColumnsByPosition("SELECT * FROM t")).containsExactly((String) null);
        assertThat(validator.projectionColumnsByPosition("SELECT count(*) FROM t"))
                .containsExactly((String) null);
        assertThat(validator.projectionColumnsByPosition("SELECT id FROM t")).containsExactly("ID");
    }

    @Test
    @DisplayName("派生表/子查询探测：FROM/JOIN 后括号命中，普通 FROM/WHERE 子查询不误判")
    void should_detectDerivedTable_when_subquery() {
        assertThat(validator.hasDerivedTable("SELECT p FROM (SELECT phone AS p FROM customer) t")).isTrue();
        assertThat(validator.hasDerivedTable("SELECT * FROM customer c JOIN (SELECT id FROM x) s ON c.id = s.id"))
                .isTrue();
        assertThat(validator.hasDerivedTable("SELECT id FROM customer WHERE phone = '138'")).isFalse();
        assertThat(validator.hasDerivedTable("SELECT id FROM customer WHERE id IN (SELECT id FROM x)")).isFalse();
        assertThat(validator.hasDerivedTable("SELECT id FROM customer")).isFalse();
        assertThat(validator.hasDerivedTable(null)).isFalse();
        assertThat(validator.hasDerivedTable("   ")).isFalse();
    }

    @Test
    @DisplayName("派生表探测覆盖逗号连接多表形态，普通逗号多表/WHERE 子查询不误判")
    void should_detectDerivedTable_when_commaMultiTableSubquery() {
        // 逗号连接多表派生表：FROM a, (SELECT ...) x
        assertThat(validator.hasDerivedTable(
                "SELECT p FROM a, (SELECT phone AS p FROM customer) x WHERE a.id = x.id")).isTrue();
        // JOIN 普通表后再逗号连接派生表
        assertThat(validator.hasDerivedTable(
                "SELECT p FROM a JOIN b ON a.id = b.id, (SELECT phone AS p FROM customer) x WHERE a.id = x.id"))
                .isTrue();
        // 普通逗号多表（无括号子查询表源）不应误判为派生表
        assertThat(validator.hasDerivedTable(
                "SELECT a.id FROM a, customer x WHERE a.id = x.id")).isFalse();
        // WHERE 子句中的 IN 子查询不是表源，不应误判
        assertThat(validator.hasDerivedTable(
                "SELECT id FROM a WHERE id IN (SELECT phone FROM customer)")).isFalse();
    }

    @Test
    @DisplayName("逗号连接多表派生表场景投影来源不可信（按位置返回 null、列集合空）")
    void should_positionalProjection_when_commaDerivedTable() {
        String sql = "SELECT p FROM a, (SELECT phone AS p FROM customer) x WHERE a.id = x.id";
        // 与单派生表同族：外层 p 可能是内层别名重命名，来源不可信，禁止按真实列放行
        assertThat(validator.projectionColumnsByPosition(sql)).containsExactly((String) null);
        assertThat(validator.projectionColumns(sql)).isEmpty();
    }

    @Test
    @DisplayName("派生表场景按位置投影列来源不可信（返回 null，走保守兜底封堵别名重命名绕过）")
    void should_positionalProjection_when_derivedTable() {
        // 子查询内 phone AS p 后外层引用 p：解析为不可信来源（null），禁止按伪真实列 P 跳过保守兜底
        assertThat(validator.projectionColumnsByPosition("SELECT p FROM (SELECT phone AS p FROM customer) t"))
                .containsExactly((String) null);
        assertThat(validator.projectionColumnsByPosition("SELECT p, id FROM (SELECT phone AS p, id FROM customer) t"))
                .containsExactly((String) null, (String) null);
    }

    @Test
    @DisplayName("派生表场景投影列集合返回空（列级白名单 fail-closed 拒绝，不做错误列级判断）")
    void should_returnEmpty_when_derivedTable_projectionColumns() {
        assertThat(validator.projectionColumns("SELECT p FROM (SELECT phone AS p FROM customer) t")).isEmpty();
    }

    @Test
    @DisplayName("校验文本去除注释后不含隐藏分号（执行与校验同一文本）")
    void should_stripComments_when_commentHidesSemicolon() {
        String sql = "SELECT 1\n-- ;\nDROP TABLE t";
        // 去注释后的规范化文本无分号、无可执行第二语句，校验面=执行面一致
        assertThat(validator.forValidation(sql)).doesNotContain(";");
        assertThat(validator.forValidation("SELECT 1 /* ; DROP */ , 2")).doesNotContain(";");
    }
}
