package com.demo.contract.extract.evidence;

/**
 * 对齐失败的原因。
 *
 * <p>区分原因不是为了好看：用户与排查者能采取的行动不同。
 * <ul>
 *   <li>{@link #NOT_FOUND} —— 模型给了原文里不存在的内容（幻觉），或引用被大幅改写</li>
 *   <li>{@link #AMBIGUOUS} —— 同一句话在文中出现多次，机器无法判断指的是哪一处</li>
 *   <li>{@link #TOO_SHORT} —— 引文过短，命中它没有证据价值（"甲方"能命中全文）</li>
 *   <li>{@link #INVALID_INPUT} —— 输入本身有问题（空引文、无原文）</li>
 * </ul>
 */
public enum AlignmentFailure {

    /** 引文在原文中找不到。 */
    NOT_FOUND,

    /** 引文在原文中多处出现，无法消歧。 */
    AMBIGUOUS,

    /** 引文过短，不足以作为证据。 */
    TOO_SHORT,

    /** 输入非法（空引文、空原文）。 */
    INVALID_INPUT
}
