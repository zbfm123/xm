package com.demo.contract.rule.domain;

/**
 * 要素字段的键名。
 *
 * <p>用枚举而不是散落的字符串字面量：拼错字段名是最容易发生、也最难发现的错误——
 * 规则会安静地拿不到值，然后（如果没有三态设计）判定为"通过"。
 *
 * <p>其中 {@code REGEX} 来源的字段由确定性抽取得到；其余字段可能来自
 * 正则、也可能来自 AI 抽取（见 extract 模块）。
 */
public enum ElementField {

    /** 甲方名称。 */
    PARTY_A,
    /** 乙方名称。 */
    PARTY_B,
    /** 合同金额（小写，如 128000.00）。 */
    AMOUNT,
    /** 合同金额（大写，如 壹拾贰万捌仟元整）。 */
    AMOUNT_IN_WORDS,
    /** 签署日期。 */
    SIGN_DATE,
    /** 生效日期。 */
    EFFECTIVE_DATE,
    /** 到期日期。 */
    EXPIRY_DATE,
    /** 付款方式/条件。 */
    PAYMENT_TERM,
    /** 争议解决方式。 */
    DISPUTE_RESOLUTION
}
