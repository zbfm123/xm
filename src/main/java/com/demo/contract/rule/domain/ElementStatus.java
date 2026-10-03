package com.demo.contract.rule.domain;

/**
 * 单个要素字段的抽取状态。
 *
 * <p>取值语义与 {@link ElementLookup} 的约定一致：
 * <ul>
 *   <li>{@link #CONFIRMED} / {@link #LOW_CONFIDENCE} → <b>有值</b></li>
 *   <li>{@link #UNKNOWN} / {@link #CONFLICT} → <b>无值</b>，规则必须判 UNDETERMINED</li>
 * </ul>
 */
public enum ElementStatus {

    /** 抽取成功且置信度达标。 */
    CONFIRMED,

    /**
     * 抽取成功但置信度低于阈值。
     *
     * <p><b>仍然有值</b>：规则可以照常参与计算，但结论需要人工确认。
     * 这与"没有值"是两件事，混起来会导致要么过度转人工、要么在缺数据时给出结论。
     */
    LOW_CONFIDENCE,

    /** 抽取失败或对齐不上——<b>没有值</b>。 */
    UNKNOWN,

    /**
     * 多个来源给出不同结果（例如正则与模型不一致）。
     *
     * <p><b>没有值</b>：冲突本身就是需要人看的信息，规则不应该替人挑一个。
     */
    CONFLICT
}
