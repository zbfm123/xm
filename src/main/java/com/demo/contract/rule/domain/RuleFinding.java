package com.demo.contract.rule.domain;

/**
 * 一条规则的完整结论，准备落库与展示。
 *
 * <p>与 {@link RuleOutcome} 的分工：{@code RuleOutcome} 是"规则返回的原始结果"，
 * 本记录是"引擎补齐元信息后的结论"——带上了规则编码、级别、说明，
 * 以及<b>执行是否出错</b>。
 *
 * @param ruleCode   规则编码
 * @param ruleName   规则名称
 * @param severity   严重级别
 * @param result     三态结果
 * @param evidence   命中依据
 * @param charStart  证据起止（归一化文本坐标）
 * @param detail     补充说明
 * @param errorMessage 规则自身执行出错时的信息；非 null 表示这条<b>规则坏了</b>，
 *                     而不是"合同有问题"
 */
public record RuleFinding(
        String ruleCode,
        String ruleName,
        Severity severity,
        RuleResult result,
        String evidence,
        Integer charStart,
        Integer charEnd,
        String detail,
        String errorMessage
) {

    public static RuleFinding of(Rule rule, RuleOutcome outcome) {
        return new RuleFinding(
                rule.code(), rule.name(), rule.severity(),
                outcome.result(), outcome.evidence(),
                outcome.charStart(), outcome.charEnd(), outcome.detail(),
                null);
    }

    /**
     * 规则执行抛异常时的结论。
     *
     * <p>注意 {@code result} 取 {@link RuleResult#UNDETERMINED} 而不是 PASS——
     * 规则坏了意味着"这条判断没有产出"，绝不能表现成"没问题"。
     * 同时错误信息单独放在 {@code errorMessage}，让前端能区分
     * "合同有问题"与"规则坏了"。
     */
    public static RuleFinding error(Rule rule, Throwable cause) {
        String msg = cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return new RuleFinding(
                rule.code(), rule.name(), rule.severity(),
                RuleResult.UNDETERMINED, null, null, null,
                "规则执行失败，该条判断未产出", msg);
    }

    public boolean isHit() {
        return result == RuleResult.HIT;
    }

    public boolean isUndetermined() {
        return result == RuleResult.UNDETERMINED;
    }

    /** 是否因为规则自身出错而没有产出。 */
    public boolean hasError() {
        return errorMessage != null;
    }

    public boolean hasLocation() {
        return charStart != null && charEnd != null;
    }
}
