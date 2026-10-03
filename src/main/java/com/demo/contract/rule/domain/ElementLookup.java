package com.demo.contract.rule.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 要素取值入口。
 *
 * <p><b>接口刻意用 {@link Optional} 而不是返回 null。</b> 原因是这套 API 要"逼"规则作者
 * 面对缺失的情况：写 {@code getAmount().orElseThrow()} 或
 * {@code if (getAmount().isEmpty()) return UNDETERMINED;} 都会让人停一下，
 * 而 {@code BigDecimal a = getAmount();} 拿到 null 之后很容易直接往下算。
 *
 * <p>字段状态与取值的对应关系（由实现保证）：
 * <ul>
 *   <li>{@code CONFIRMED} → 有值，且已确认</li>
 *   <li>{@code LOW_CONFIDENCE} → 有值，但置信度低（规则可以照常算，但结论需人工确认）</li>
 *   <li>{@code UNKNOWN} / {@code CONFLICT} → <b>无值</b>，规则必须走 UNDETERMINED</li>
 * </ul>
 *
 * <p>注意区分"有值但置信度低"与"没有值"：前者能算，后者不能算。
 * 把两者混为一谈会导致要么过度转人工、要么在缺数据时给出结论。
 */
public interface ElementLookup {

    Optional<String> getText(ElementField field);

    Optional<BigDecimal> getAmount(ElementField field);

    Optional<LocalDate> getDate(ElementField field);

    /** 字段的状态。未登记的字段视为 {@link ElementStatus#UNKNOWN}。 */
    ElementStatus getStatus(ElementField field);

    /** 字段在原文中的位置，用于展示证据。可能为空。 */
    Optional<int[]> getOriginalRange(ElementField field);

    /**
     * 字段是否可以用于确定性判断。
     *
     * <p>仅当状态为 {@code CONFIRMED} 或 {@code LOW_CONFIDENCE} 时返回 true——
     * 这两种情况都有实际取值；后者的低置信度会在结论层面体现，而不是让规则算不了。
     */
    default boolean isUsable(ElementField field) {
        ElementStatus status = getStatus(field);
        return status == ElementStatus.CONFIRMED || status == ElementStatus.LOW_CONFIDENCE;
    }

    /** 是否所有给定字段都可用。规则常用它做前置检查。 */
    default boolean allUsable(ElementField... fields) {
        for (ElementField f : fields) {
            if (!isUsable(f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 缺失字段的说明，用于写入 UNDETERMINED 的结论理由。
     *
     * <p>必须具体到"缺哪个字段"——只写"数据不足"对排查毫无帮助。
     */
    default String describeMissing(ElementField... fields) {
        StringBuilder sb = new StringBuilder();
        for (ElementField f : fields) {
            if (!isUsable(f)) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(f.name()).append('(').append(getStatus(f)).append(')');
            }
        }
        return sb.toString();
    }
}
