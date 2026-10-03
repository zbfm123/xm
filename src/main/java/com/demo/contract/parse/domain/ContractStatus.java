package com.demo.contract.parse.domain;

/**
 * 合同审查状态机。
 *
 * <p>完整定义参照 [02-架构] 与 workflow 模块笔记。**状态迁移由 workflow 模块驱动**，
 * 本枚举只定义状态集合与合法性判断，避免状态名将来再改一次。
 *
 * <p>一个容易被写错的地方：<b>{@code AI_UNAVAILABLE} 不是终态</b>。
 * AI 不可用时应当继续流转到 {@code REVIEWING}（人工仍可完成审查），
 * 而不是把整份合同判死——这就是不变式 I-04。
 */
public enum ContractStatus {

    /** 已上传，文件已落盘，等待解析。 */
    UPLOADED,
    /** 解析中。 */
    PARSING,
    /** 解析失败（加密、损坏、无可提取文本）。终态。 */
    PARSE_FAILED,
    /** 解析完成，文本可用于抽取。 */
    PARSED,
    /** 规则校验完成。 */
    RULE_CHECKED,
    /** 审查完成，结论已生效。终态。 */
    COMPLETED,
    /** 已删除（软删除）。终态。 */
    DELETED;

    /** 是否为终态：终态不接受任何迁移。 */
    public boolean isTerminal() {
        return this == PARSE_FAILED || this == COMPLETED || this == DELETED;
    }

    /**
     * 判断从当前状态迁移到 {@code target} 是否合法。
     *
     * <p>合法集合刻意收得很紧：宁可拒绝一次合法迁移（改代码加一行），
     * 也不要放过一次非法迁移——后者意味着数据进入了没人设计过的状态。
     */
    public boolean canMoveTo(ContractStatus target) {
        if (target == null || this == target) {
            return false;
        }
        if (isTerminal()) {
            // 例外：允许从任何非终态进入 DELETED，但终态之间不能互相迁移
            return target == DELETED && this != DELETED;
        }
        return switch (this) {
            case UPLOADED -> target == PARSING || target == DELETED;
            case PARSING -> target == PARSED || target == PARSE_FAILED || target == DELETED;
            case PARSED -> target == RULE_CHECKED || target == DELETED;
            case RULE_CHECKED -> target == COMPLETED || target == DELETED;
            default -> false;
        };
    }

    public static ContractStatus from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("合同状态不能为空");
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知合同状态: " + value);
        }
    }
}
