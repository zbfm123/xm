package com.demo.contract.parse.text;

/**
 * 文本提取结果。
 *
 * @param rawText    提取到的原文（未归一化）
 * @param pageCount  页数。PDF 为实际页数；DOCX 无固定分页，按 1 处理
 *                   （因此 DOCX 不会触发页眉页脚清理——这是刻意的保守选择）
 */
public record ExtractionResult(String rawText, int pageCount) {

    /**
     * 是否没有可提取的文本。
     *
     * <p>用于识别"疑似扫描件"：一份图片型 PDF 能成功打开、页数正常，
     * 但提取出来的全是空白。这种情况必须<b>显式报告</b>，
     * 而不是返回空文本让下游以为"这份合同没有风险条款"。
     */
    public boolean isEmpty() {
        return rawText == null || rawText.replaceAll("\\s", "").isEmpty();
    }

    public int rawLength() {
        return rawText == null ? 0 : rawText.length();
    }
}
