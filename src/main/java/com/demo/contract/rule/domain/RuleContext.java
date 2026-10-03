package com.demo.contract.rule.domain;

/**
 * 规则执行时能看到的一切输入。
 *
 * <p><b>关键设计：所有外部输入都在这里显式注入，规则本身不读时钟、不读配置、不联网。</b>
 *
 * <p>为什么这么严：规则一旦调用 {@code LocalDate.now()}，"合同是否已过期"这类判断
 * 就不再可复现——今天是这个结论，明天是另一个。测试也没法固定时间。
 * 把时钟作为参数传进来，测试里传固定值，生产里传系统时间，
 * 这样"同输入必然同输出"（不变式 I-03）才成立。
 *
 * <p>⚠️ 本记录<b>不含原始文本</b>：规则只需要按字段取值。
 * 需要文本的规则（例如"必备条款是否存在"）走另一个入口，
 * 避免把整篇合同文本在规则之间传来传去。
 */
public record RuleContext(
        ElementLookup elements,
        String normalizedText,
        java.time.LocalDate today
) {

    public RuleContext {
        if (elements == null) {
            throw new IllegalArgumentException("elements 不能为 null");
        }
        if (normalizedText == null) {
            normalizedText = "";
        }
        if (today == null) {
            throw new IllegalArgumentException("today 必须显式注入，规则不得自行读取系统时间");
        }
    }

    /** 供不需要文本的规则使用。 */
    public static RuleContext of(ElementLookup elements, java.time.LocalDate today) {
        return new RuleContext(elements, "", today);
    }
}
