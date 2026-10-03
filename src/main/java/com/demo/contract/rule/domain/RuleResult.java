package com.demo.contract.rule.domain;

/**
 * 规则的执行结果。
 *
 * <p><b>三态设计是本模块最重要的一个决定</b>：把"无法判定"从"没有命中"里分离出来。
 *
 * <p>反例是很自然的写法——规则返回 boolean：
 * <pre>
 * if (element == null) return false;   // ← 严重的错误
 * </pre>
 * 这意味着"要素抽不到"会被当成"这条规则通过了"。用户看到的结论是"合同合规"，
 * 而真相是"系统根本不知道"。**在信息不足时输出"合规"，比报错危险得多。**
 *
 * <p>因此：
 * <ul>
 *   <li>{@link #HIT} —— 确定存在该问题</li>
 *   <li>{@link #PASS} —— 确定不存在该问题</li>
 *   <li>{@link #UNDETERMINED} —— 输入不足，<b>无法判定</b>，需人工介入</li>
 * </ul>
 */
public enum RuleResult {

    /** 命中：确定存在该问题。 */
    HIT,

    /** 通过：确定不存在该问题。 */
    PASS,

    /**
     * 无法判定：输入不足（要素缺失、文本异常等）。
     *
     * <p>必须在报告中显示为"待人工确认"，<b>绝不能折叠进 PASS</b>。
     */
    UNDETERMINED
}
