package com.demo.contract.review;

/**
 * 人工复核相关错误码。
 *
 * <p>与 {@code ParseErrorCode} / {@code AuthErrorCode} 同构：**每个码对应一种
 * 调用方能采取不同行动的情况**。把它们合并成一个"复核失败"会让前端不知道
 * 该提示"换个人来做"还是"这条已经处理过了"。
 *
 * <h2>⚠️ 为什么单独建一个枚举（而不是塞进 ParseErrorCode）</h2>
 *
 * 2026-10-07 补这两个码时，{@code ParseErrorCode} 与 {@code AuthErrorCode} 都已存在。
 * 复核既不属于"解析合同"，也不属于"认证"——它是工作流域的规则。
 * 沿用现有的分层（认证/解析/复核各一套码），比往解析枚举里塞一个
 * {@code INSUFFICIENT_ROLE} 更不容易让后来者困惑。
 *
 * <h2>这两个码是被文档"提前写出来"的</h2>
 *
 * {@code docs/modules/workflow.md} 早就列出了 {@code INSUFFICIENT_ROLE}（403）
 * 与 {@code ALREADY_REVIEWED}（409），README 也写明"staff01 无权终审低置信度结论"——
 * 但代码里一直没有实现，全仓 grep 不到这两个名字。
 * <b>文档承诺了、代码没有</b>，正是这次审计要消除的那类问题。
 */
public enum ReviewErrorCode {

    /**
     * 角色不足：低置信度条目的终审（采纳/驳回）只有法务主管可做。
     *
     * <p>调用方能采取的行动是「换一个有权限的账号」，而不是改参数——所以是 403 而非 400。
     */
    INSUFFICIENT_ROLE,

    /**
     * 该条已经被复核过（处于终态），不能再次裁决。
     *
     * <p>这是<b>并发与重复提交</b>的共同防线：调用方应当刷新页面看当前状态。
     * 与 403 区分开的意义在于：前者是"你不该做"，后者是"这事已经定了"。
     */
    ALREADY_REVIEWED
}
