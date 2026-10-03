package com.demo.contract.rule.engine;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.ElementLookup;
import com.demo.contract.rule.domain.ElementStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ElementLookup} 的通用实现：由代码显式搭建。
 *
 * <p>用途有两个：
 * <ol>
 *   <li><b>规则单元测试</b>——直接构造想要的要素组合，不碰数据库</li>
 *   <li><b>未来的抽取结果适配</b>——extract 模块把 {@code ContractElement} 行
 *       转成这个对象再交给规则，避免 rule 直接依赖 extract 的持久化结构</li>
 * </ol>
 *
 * <p>刻意不把"取不到值"与"值为空"混在一起：只有状态为 CONFIRMED 或
 * LOW_CONFIDENCE 时 {@link #getText} 才会返回值，其他状态返回空 Optional。
 */
public final class MapElementLookup implements ElementLookup {

    private final Map<ElementField, Object> values = new EnumMap<>(ElementField.class);
    private final Map<ElementField, ElementStatus> statuses = new EnumMap<>(ElementField.class);
    private final Map<ElementField, int[]> ranges = new EnumMap<>(ElementField.class);

    private MapElementLookup() {
    }

    public static MapElementLookup builder() {
        return new MapElementLookup();
    }

    // ---------------- 构建 ----------------

    public MapElementLookup text(ElementField field, String value) {
        return text(field, value, ElementStatus.CONFIRMED);
    }

    public MapElementLookup text(ElementField field, String value, ElementStatus status) {
        statuses.put(field, status);
        if (status == ElementStatus.UNKNOWN || status == ElementStatus.CONFLICT) {
            // 无值语义：即使传了值也不保留，避免"状态未知却取到了值"的歧义
            values.remove(field);
            return this;
        }
        values.put(field, value);
        return this;
    }

    public MapElementLookup amount(ElementField field, String value) {
        return amount(field, value == null ? null : new BigDecimal(value), ElementStatus.CONFIRMED);
    }

    public MapElementLookup amount(ElementField field, BigDecimal value, ElementStatus status) {
        statuses.put(field, status);
        if (status == ElementStatus.UNKNOWN || status == ElementStatus.CONFLICT) {
            values.remove(field);
            return this;
        }
        values.put(field, value);
        return this;
    }

    public MapElementLookup date(ElementField field, String isoDate) {
        return date(field, isoDate == null ? null : LocalDate.parse(isoDate), ElementStatus.CONFIRMED);
    }

    public MapElementLookup date(ElementField field, LocalDate value, ElementStatus status) {
        statuses.put(field, status);
        if (status == ElementStatus.UNKNOWN || status == ElementStatus.CONFLICT) {
            values.remove(field);
            return this;
        }
        values.put(field, value);
        return this;
    }

    /** 标记字段无值。 */
    public MapElementLookup unknown(ElementField field) {
        statuses.put(field, ElementStatus.UNKNOWN);
        values.remove(field);
        return this;
    }

    /** 标记字段多来源冲突。 */
    public MapElementLookup conflict(ElementField field) {
        statuses.put(field, ElementStatus.CONFLICT);
        values.remove(field);
        return this;
    }

    /** 记录字段在归一化文本中的位置，供证据高亮。 */
    public MapElementLookup range(ElementField field, int start, int end) {
        ranges.put(field, new int[]{start, end});
        return this;
    }

    /** 未登记的字段默认为 UNKNOWN——"没写"就应当被当成"不知道"。 */
    public MapElementLookup defaultUnknown(ElementField... fields) {
        for (ElementField f : fields) {
            statuses.putIfAbsent(f, ElementStatus.UNKNOWN);
        }
        return this;
    }

    // ---------------- 读取 ----------------

    @Override
    public Optional<String> getText(ElementField field) {
        if (!isUsable(field)) {
            return Optional.empty();
        }
        Object v = values.get(field);
        return v instanceof String s ? Optional.of(s) : Optional.empty();
    }

    @Override
    public Optional<BigDecimal> getAmount(ElementField field) {
        if (!isUsable(field)) {
            return Optional.empty();
        }
        Object v = values.get(field);
        return v instanceof BigDecimal d ? Optional.of(d) : Optional.empty();
    }

    @Override
    public Optional<LocalDate> getDate(ElementField field) {
        if (!isUsable(field)) {
            return Optional.empty();
        }
        Object v = values.get(field);
        return v instanceof LocalDate d ? Optional.of(d) : Optional.empty();
    }

    @Override
    public ElementStatus getStatus(ElementField field) {
        return statuses.getOrDefault(field, ElementStatus.UNKNOWN);
    }

    @Override
    public Optional<int[]> getOriginalRange(ElementField field) {
        return Optional.ofNullable(ranges.get(field));
    }
}
