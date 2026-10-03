package com.demo.contract.extract.evidence;

/**
 * 证据匹配的级别。
 *
 * <p>分级的本质是<b>把"匹配有多硬"显式变成一个可判断的量</b>，
 * 从而让下游能据此调整置信度。
 *
 * <p>如果没有分级，只有"匹配上/匹配不上"两种结果，那么"改了一个字的引文"
 * 与"逐字相同的引文"会被同等对待，而它们的可信度显然不同。
 */
public enum MatchLevel {

    /** 逐字命中。可信度最高。 */
    EXACT,

    /**
     * 归一化后命中（全角/半角、空白差异）。
     *
     * <p>需要把归一化坐标<b>回映射</b>到原文坐标，这是最容易写错的一步。
     */
    NORMALIZED,

    /** 模糊命中（存在少量差异）。可用，但必须下调置信度。 */
    FUZZY,

    /** 未命中。调用方必须据此降级为人工，不得使用近似位置。 */
    NONE;

    /** 该级别对应的置信度权重，供综合评分使用。 */
    public double weight() {
        return switch (this) {
            case EXACT -> 1.0;
            case NORMALIZED -> 0.9;
            case FUZZY -> 0.7;
            case NONE -> 0.0;
        };
    }
}
