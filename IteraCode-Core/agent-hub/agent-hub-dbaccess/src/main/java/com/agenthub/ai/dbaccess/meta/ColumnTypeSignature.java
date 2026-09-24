package com.agenthub.ai.dbaccess.meta;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 列类型签名规则（data-model.md t_type_signature_rule）：类型名 + 长度/精度的确定性合成。
 *
 * <p>本类是「类型签名」的<b>唯一实现</b>：快照指纹（{@code SchemaSnapshotVO.columnsHashOf}）与字典落库
 * （{@code kb_column.data_type}）必须共用本类，保证「检测口径」与「落库口径」永不漂移。</p>
 *
 * <p>规则（纯函数、null 安全）：</p>
 * <ol>
 *   <li>{@code dataType} 空/空白 → {@code ""}；</li>
 *   <li>已含 {@code (}（Provider 已带参数）→ 原样返回；</li>
 *   <li>数值族且 {@code numericPrecision != null} → {@code baseName(精度,标度)}（标度缺省 0）；</li>
 *   <li>长度族且 {@code columnSize != null && > 0} → {@code baseName(长度)}；</li>
 *   <li>其余 → 裸类型名。</li>
 * </ol>
 *
 * <p>整数族（{@code int}/{@code bigint} 等）<b>有意</b>不在数值族白名单：只比类型名、不比显示宽度
 * （避免 MySQL {@code int(11)} 这类显示宽度噪声导致误报结构变化）。</p>
 */
public final class ColumnTypeSignature {

    /** 数值族：签名携带（精度,标度）；整数族有意不纳入 */
    private static final Set<String> NUMERIC_FAMILY = Set.of(
            "decimal", "numeric", "number", "dec", "fixed", "float", "double", "real");

    /** 长度族：签名携带（长度） */
    private static final Set<String> LENGTH_FAMILY = Set.of(
            "char", "varchar", "nchar", "nvarchar", "varchar2", "nvarchar2",
            "binary", "varbinary", "raw", "bit", "varbit");

    private ColumnTypeSignature() {
    }

    /**
     * 合成列类型签名。
     *
     * @param column 中立列元数据（可为 null）
     * @return 类型签名；{@code column == null} 或类型为空/空白 → {@code ""}
     */
    public static String signature(ColumnMeta column) {
        if (column == null) {
            return "";
        }
        String base = base(column.getDataType());
        if (base.isEmpty()) {
            return "";
        }
        if (base.indexOf('(') >= 0) {
            // Provider 已返回带参数类型，原样保留（仅做 trim + 小写归一，避免二次拼装）
            return base;
        }
        String baseName = baseName(base);
        Integer precision = column.getNumericPrecision();
        if (NUMERIC_FAMILY.contains(baseName) && precision != null) {
            Integer scale = column.getNumericScale();
            // 标度缺失视为 0：Oracle NUMBER(10) 的 DATA_SCALE 为 NULL，语义等价于 (10,0)
            return baseName + "(" + precision + "," + (scale == null ? 0 : scale) + ")";
        }
        Integer size = column.getColumnSize();
        if (LENGTH_FAMILY.contains(baseName) && size != null && size > 0) {
            return baseName + "(" + size + ")";
        }
        return baseName;
    }

    /**
     * 判定是否为「签名补全」：旧值为无长度基线（如 {@code varchar}）且新值为同基类型签名（如 {@code varchar(64)}）
     * → 视作等价，不计入结构变更（供 kb 落库侧复用，避免升级后一次性误降级置信度）。
     *
     * @param oldType 旧库中已落库的类型值（如 {@code varchar}，可为 null）
     * @param newType 本次采集的类型签名（如 {@code varchar(64)}，可为 null）
     * @return 等价=true；真实的类型变化（如 {@code varchar(64) → varchar(128)}、{@code int → bigint}）=false
     */
    public static boolean isSignatureBackfill(String oldType, String newType) {
        if (Objects.equals(oldType, newType)) {
            return true;
        }
        String oldBase = base(oldType);
        String newBase = base(newType);
        return oldBase.indexOf('(') < 0
                && newBase.indexOf('(') >= 0
                && baseName(oldBase).equalsIgnoreCase(baseName(newBase));
    }

    /** 类型名归一：trim + 小写（null/空 → 空串） */
    private static String base(String dataType) {
        return dataType == null ? "" : dataType.trim().toLowerCase(Locale.ROOT);
    }

    /** 裸类型名：截取首个 {@code (} 之前的部分并去除全部空白 */
    private static String baseName(String base) {
        int idx = base.indexOf('(');
        String name = idx >= 0 ? base.substring(0, idx) : base;
        return name.replaceAll("\\s+", "");
    }
}
