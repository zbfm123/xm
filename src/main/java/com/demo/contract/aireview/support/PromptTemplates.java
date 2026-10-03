package com.demo.contract.aireview.support;

import org.springframework.stereotype.Component;

/**
 * 提示词模板。
 *
 * <p>集中在一处是为了让<b>提示词版本可追溯</b>：版本号进缓存键
 * （决策 D-05/D-20），改了提示词而缓存键不变会命中旧提示词的结论，
 * 而且看起来完全正常。
 *
 * <p>三条共同的输出约定：
 * <ol>
 *   <li>只输出 JSON，不要解释文字</li>
 *   <li><b>每个结论必须附上原文引文</b>——这是后续证据对齐的输入，
 *       没有它整条链路都无法核验</li>
 *   <li>引文必须是原文的<b>连续片段</b>，不要改写、不要拼接</li>
 * </ol>
 */
@Component
public class PromptTemplates {

    /** 提示词版本。改动模板文本时**必须同步递增**，否则缓存会命中旧结论。 */
    public static final String PROMPT_VERSION = "v1";

    private static final String ELEMENT_EXTRACTION = """
            你是合同要素抽取助手。请从用户给出的合同正文中抽取结构化要素。

            输出要求（严格遵守）：
            1. 只输出 JSON，格式为 {"elements": {"<字段名>": {"value": "...", "quote": "...", "confidence": 0.0~1.0}}}
            2. 需要抽取的字段：partyA（甲方名称）、partyB（乙方名称）、amount（合同金额数字）、
               amountInWords（金额大写）、signDate（签订日期，YYYY-MM-DD）、effectiveDate（生效日期）、
               expiryDate（到期日期）、paymentTerm（付款方式）、disputeResolution（争议解决方式）
            3. 每个字段必须附上 quote，且 quote 必须是<b>原文中连续出现的一段文字</b>，
               不要改写、不要拼接、不要省略中间的字。
            4. 原文中找不到的字段<b>直接省略</b>，不要猜测、不要填默认值。
            5. confidence 是你对该字段值的把握程度，0 到 1 之间的小数。
            """;

    private static final String RISK_REVIEW = """
            你是合同风险条款识别助手。请从用户给出的合同正文中找出对一方明显不利或表述模糊的条款。

            输出要求（严格遵守）：
            1. 只输出 JSON，格式为 {"findings": [{"riskType": "...", "quote": "...", "confidence": 0.0~1.0, "reason": "..."}]}
            2. riskType 只能取以下值之一：
               UNLIMITED_LIABILITY（责任无上限）、UNILATERAL_TERMINATION（单方无理由解除）、
               AUTO_RENEWAL（自动续约无退出方式）、VAGUE_PAYMENT（付款条件不明确）、
               UNCAPPED_PENALTY（违约金过高或计算不明）、CONFIDENTIALITY_GAP（保密义务单方或缺失）、
               JURISDICTION_UNCLEAR（争议解决方式不明确）、UNKNOWN_RISK（有风险但无法归类）
            3. 每个结论必须附上 quote，且 quote 必须是<b>原文中连续出现的一段文字</b>，
               不要改写、不要拼接。系统会用这段引文回到原文核对，
               引文在原文中找不到的结论会被丢弃并转人工。
            4. confidence 是你对"这段引文确实构成该风险"的把握程度，0 到 1 之间的小数。
            5. reason 用一句话说明判断依据。
            6. 不要为了凑数而报告风险。宁可少报，也不要报出你并不确信的内容。
            """;

    public String elementExtractionSystemPrompt() {
        return ELEMENT_EXTRACTION;
    }

    public String riskReviewSystemPrompt() {
        return RISK_REVIEW;
    }

    /** 系统提示词里含"要素抽取"字样——Mock 桩靠它区分任务类型。 */
    public boolean isElementPrompt(String systemPrompt) {
        return systemPrompt != null && systemPrompt.contains("要素抽取");
    }

    /** 系统提示词里含"风险条款"字样——Mock 桩靠它区分任务类型。 */
    public boolean isRiskPrompt(String systemPrompt) {
        return systemPrompt != null && systemPrompt.contains("风险条款");
    }
}
