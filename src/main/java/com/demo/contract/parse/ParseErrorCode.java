package com.demo.contract.parse;

/**
 * 合同解析相关错误码。
 *
 * <p>每个码对应一种<b>用户能采取不同行动</b>的情况：
 * 换文件、解密后重传、稍后重试、联系运维。把它们合并成一个"解析失败"
 * 会让用户完全不知道下一步做什么。
 */
public enum ParseErrorCode {

    /** 文件超过大小上限。换文件。 */
    FILE_TOO_LARGE,
    /** 扩展名与实际内容（魔数）不符。换文件。 */
    MIME_MISMATCH,
    /** 报告中的大小与实际读取的字节数不一致，属客户端异常或传输中断。 */
    SIZE_MISMATCH,
    /** 文件为空。 */
    EMPTY_FILE,
    /** 加密的 PDF，需要用户解密后重传。 */
    PDF_ENCRYPTED,
    /** 提取结果为空，疑似扫描件（本期明确不支持）。 */
    NO_EXTRACTABLE_TEXT,
    /** 文件损坏或格式不受支持。 */
    PARSE_FAILED,
    /**
     * 合同不存在，或存在但不属于当前租户。
     *
     * <p>这两种情况刻意合并为同一个码：如果区分开，接口就变成了
     * "某个合同 id 是否存在"的探测器。
     */
    CONTRACT_NOT_FOUND,
    /** 存储不可写或文件丢失。稍后重试或联系运维。 */
    STORAGE_UNAVAILABLE
}
