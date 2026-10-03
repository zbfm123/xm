package com.demo.contract.review.domain;

/**
 * 人工复核动作类型。
 *
 * <p>枚举而不是自由文本：动作集合是业务约定的一部分，
 * 允许任意字符串会让"按动作统计"与"按动作过滤"都变得不可靠。
 */
public enum ReviewActionType {

    /** 采纳该风险结论，进入报告正文。 */
    ACCEPT("采纳，进入报告正文"),

    /** 驳回该风险结论：不构成风险，不进报告。 */
    REJECT("驳回，不构成风险"),

    /** 升级给主管复核（例如置信度偏低但影响较大）。 */
    ESCALATE("升级主管复核"),

    /** 退回要求补充信息，不能给出结论。 */
    NEED_INFO("信息不足，退回补充"),

    /**
     * 确认"整份合同无风险"。
     *
     * <p>刻意做成<b>独立的动作</b>而不是"所有条目的 ACCEPT 之和"：
     * "没有人看"与"看过了且确认无风险"必须能被区分，
     * 否则报告里的一句"无风险"无法追溯到底是谁认定的。
     */
    CONFIRM_NO_RISK("确认整份合同无风险");

    private final String displayName;

    ReviewActionType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
