package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 涉及表引用（SqlFeature.tables）：{schema, alias, name}。
 *
 * <p>{@code name} 始终为纯表名；{@code schema} 为可空的库/Schema 限定（跨库时如 {@code DB_A}），
 * 二者共同构成「schema.table」限定标识，供跨库校验按限定名匹配。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableRef {

    /** 库/Schema 限定（可空；跨库时如 DB_A） */
    private String schema;

    /** 表别名（可空） */
    private String alias;

    /** 表名（不含别名、不含 schema） */
    private String name;

    /**
     * 限定名：schema 非空 → "schema.name"，否则纯表名 name。
     */
    public String qualifiedName() {
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    @Override
    public String toString() {
        return alias == null || alias.isBlank() ? name : name + " " + alias;
    }
}
