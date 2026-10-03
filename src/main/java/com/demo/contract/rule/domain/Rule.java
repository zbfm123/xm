package com.demo.contract.rule.domain;

/**
 * 一条规则的定义与执行契约。
 *
 * <p>规则只描述"怎么判断"，不关心"谁来遍历"——遍历与汇合是
 * {@code RuleEngine} 的职责。这样每条规则都能被独立单测。
 *
 * <p>实现约束（写在接口注释里，因为这是最容易违反的地方）：
 * <ul>
 *   <li><b>必须是纯函数</b>：只读 {@link RuleContext}，不写外部状态</li>
 *   <li><b>不得联网</b>：本模块存在的意义就是"确定性判断不付概率代价"</li>
 *   <li><b>不得读系统时钟</b>：时间从 {@code context.today()} 取</li>
 *   <li><b>不得抛异常来表达业务结论</b>：无法判定就返回 {@code UNDETERMINED}</li>
 * </ul>
 */
public interface Rule {

    /** 规则编码，全局唯一，例如 {@code R-AMOUNT-MISMATCH}。 */
    String code();

    /** 规则名称（中文，用于展示）。 */
    String name();

    /** 严重级别。 */
    Severity severity();

    /** 规则说明：判断什么、依据什么。用于报告与人工复核。 */
    String description();

    /**
     * 执行规则。
     *
     * <p>返回 {@link RuleOutcome}，其中 {@code result} 为三态之一。
     * 未命中时也应当返回 {@code PASS} 而不是 null。
     */
    RuleOutcome evaluate(RuleContext context);
}
