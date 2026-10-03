package com.demo.contract.rule.domain;

/**
 * 单条规则的执行结果。
 *
 * @param result     三态结果
 * @param evidence   命中依据（人可读），未命中时可为 null
 * @param charStart  证据在<b>归一化文本</b>中的起始下标，可为 null
 * @param charEnd    证据在归一化文本中的结束下标（开区间），可为 null
 * @param detail     补充说明：缺失字段名、计算过程、对比的两个值等
 */
public record RuleOutcome(
        RuleResult result,
        String evidence,
        Integer charStart,
        Integer charEnd,
        String detail
) {

    public RuleOutcome {
        if (result == null) {
            throw new IllegalArgumentException("result 不能为 null：规则必须显式返回三态之一");
        }
        if (charStart != null && charEnd != null && charStart > charEnd) {
            throw new IllegalArgumentException("区间非法: [" + charStart + ", " + charEnd + ")");
        }
    }

    public static RuleOutcome hit(String evidence, Integer start, Integer end) {
        return new RuleOutcome(RuleResult.HIT, evidence, start, end, null);
    }

    public static RuleOutcome hit(String evidence, Integer start, Integer end, String detail) {
        return new RuleOutcome(RuleResult.HIT, evidence, start, end, detail);
    }

    public static RuleOutcome pass() {
        return new RuleOutcome(RuleResult.PASS, null, null, null, null);
    }

    public static RuleOutcome pass(String detail) {
        return new RuleOutcome(RuleResult.PASS, null, null, null, detail);
    }

    /**
     * 无法判定。
     *
     * @param detail 必须说明<b>缺什么</b>，例如 "AMOUNT(UNKNOWN)、AMOUNT_IN_WORDS(UNKNOWN)"。
     *               只写"数据不足"对排查没有帮助。
     */
    public static RuleOutcome undetermined(String detail) {
        return new RuleOutcome(RuleResult.UNDETERMINED, null, null, null, detail);
    }

    public boolean isHit() {
        return result == RuleResult.HIT;
    }

    public boolean isUndetermined() {
        return result == RuleResult.UNDETERMINED;
    }

    /** 证据是否带原文位置。带位置的结论才能在前端高亮核验。 */
    public boolean hasLocation() {
        return charStart != null && charEnd != null;
    }
}
