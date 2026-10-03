package com.demo.contract.review.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 审查任务状态机。
 *
 * <p>把合法迁移写成一张<b>显式的表</b>，而不是散落在各处的 {@code if}。
 * 这样"哪些迁移合法"只有一处定义，穷举测试可以逐对验证，
 * 而且新增状态时编译器不会让你忘记处理。
 *
 * <pre>
 *   PENDING ──→ IN_PROGRESS ──→ AWAITING_REVIEW ──→ COMPLETED
 *                    │                  ↑
 *                    └──→ AI_UNAVAILABLE ┘
 * </pre>
 *
 * <p>⚠️ <b>最关键的一条设计：{@code AI_UNAVAILABLE} 不是终态。</b>
 *
 * <p>它必须能回到 {@code AWAITING_REVIEW} 继续人工复核。
 * 如果把它设成失败终态，就等于"AI 挂了整个审查就废了"——
 * 直接违反不变式 **I-04**（AI 不可用时规则与人工流程不受影响）。
 *
 * <p>还有一条：{@code CANCELLED} 可以从任何非终态进入。
 * 用户放弃一份合同是正常需求，但它不能从终态进入——
 * 那样会让"已完成"变成可撤销，与审计只追加的精神冲突。
 */
public enum ReviewTaskStatus {

    /** 已创建，等待处理。 */
    PENDING("待处理"),

    /** 正在跑规则校验 / 要素抽取 / AI 审查。 */
    IN_PROGRESS("处理中"),

    /**
     * AI 通道不可用，已降级。
     *
     * <p><b>不是终态。</b> 用户可以照常走人工复核，
     * 规则结论与要素抽取的结果都还在。
     */
    AI_UNAVAILABLE("AI 不可用（已降级）"),

    /** 机器分析完成，等待人工复核。 */
    AWAITING_REVIEW("待人工复核"),

    /** 全部条目已复核完毕。 */
    COMPLETED("已完成"),

    /** 被取消。 */
    CANCELLED("已取消");

    private final String displayName;

    ReviewTaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 合法迁移表。
     *
     * <p>用 {@link EnumSet} 而不是 {@code Set.of}：它在编译期就是枚举专用的，
     * 且迭代顺序稳定（便于生成文档与断言）。
     */
    private static final Map<ReviewTaskStatus, Set<ReviewTaskStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, EnumSet.of(AWAITING_REVIEW, AI_UNAVAILABLE, CANCELLED),
            // ⚠️ 这两条是 I-04 的落点：降级后仍能进入人工复核
            AI_UNAVAILABLE, EnumSet.of(AWAITING_REVIEW, CANCELLED),
            AWAITING_REVIEW, EnumSet.of(COMPLETED, CANCELLED),
            // 终态：无出边
            COMPLETED, EnumSet.noneOf(ReviewTaskStatus.class),
            CANCELLED, EnumSet.noneOf(ReviewTaskStatus.class)
    );

    /** 是否为终态。终态不能再迁移——包括不能"取消"一个已完成的任务。 */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /** 能否迁移到目标状态。 */
    public boolean canMoveTo(ReviewTaskStatus target) {
        if (target == null) {
            return false;
        }
        return TRANSITIONS.get(this).contains(target);
    }

    /** 从本状态可以到达的所有状态（供文档与穷举测试使用）。 */
    public Set<ReviewTaskStatus> allowedTargets() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    /**
     * 全部合法迁移对，形如 {@code "PENDING->IN_PROGRESS"}。
     *
     * <p>穷举测试用它作为"期望集合"，从而在新增状态时自动覆盖。
     */
    public static Set<String> allLegalTransitions() {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (ReviewTaskStatus from : values()) {
            for (ReviewTaskStatus to : from.allowedTargets()) {
                result.add(from.name() + "->" + to.name());
            }
        }
        return result;
    }

    public static ReviewTaskStatus from(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
