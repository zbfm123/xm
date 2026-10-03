package com.demo.contract.parse.text;

import java.util.Map;

/**
 * 归一化后的文本 + 「归一化坐标 → 原文坐标」映射。
 *
 * <p>为什么必须带映射：归一化会改变字符长度（全角转半角、空白折叠、去页眉页脚），
 * 而下游 [extract] 与 [ai-review] 的证据区间必须能**回到原文**（不变式 I-02）。
 * 只保留洗干净的文本，会让所有区间整体偏移，报错时还很难发现——
 * 因为文本看起来完全正常。
 *
 * @param text           归一化文本
 * @param offsetsToOriginal 长度 = text.length() 的数组，
 *                       {@code offsetsToOriginal[i]} 是归一化文本第 i 个字符在原文中的下标
 * @param originalText   原文（提取器直接产出、未归一化），用于回查
 */
public record NormalizedText(String text, int[] offsetsToOriginal, String originalText) {

    /**
     * 把归一化区间映射回原文区间。
     *
     * <p>取 {@code [start, end)} 的首尾字符映射：起点用第 start 个字符的原下标，
     * 终点用第 end-1 个字符的原下标 +1。
     *
     * <p>若区间落在被删除的内容上（例如整个区间就是一段页眉），
     * 首尾可能指向同一位置——此时返回的区间长度为 0，调用方据此判断"无法定位"。
     *
     * @throws IndexOutOfBoundsException 区间越界时抛出，不返回"尽量接近"的结果
     */
    public int[] toOriginalRange(int start, int end) {
        if (start < 0 || end > text.length() || start > end) {
            throw new IndexOutOfBoundsException(
                    "归一化区间越界: [" + start + ", " + end + ") 文本长度=" + text.length());
        }
        if (start == end) {
            int at = start >= offsetsToOriginal.length
                    ? (offsetsToOriginal.length == 0 ? 0 : offsetsToOriginal[offsetsToOriginal.length - 1] + 1)
                    : offsetsToOriginal[start];
            return new int[]{at, at};
        }
        int from = offsetsToOriginal[start];
        int to = offsetsToOriginal[end - 1] + 1;
        return new int[]{from, to};
    }

    /** 按原文区间取出原文片段，供证据展示。 */
    public String originalSlice(int originalStart, int originalEnd) {
        int s = Math.max(0, originalStart);
        int e = Math.min(originalText.length(), originalEnd);
        return s >= e ? "" : originalText.substring(s, e);
    }

    public int length() {
        return text.length();
    }

    /** 诊断用：确认映射数组长度与文本一致（构建后立即自检，避免错位到下游才暴露）。 */
    public void assertConsistent() {
        if (offsetsToOriginal.length != text.length()) {
            throw new IllegalStateException(
                    "映射数组长度与归一化文本不一致: map=" + offsetsToOriginal.length
                            + " text=" + text.length());
        }
    }

    /** 供日志与测试摘要使用。 */
    public Map<String, Object> summary() {
        return Map.of(
                "originalLength", originalText.length(),
                "normalizedLength", text.length(),
                "charsRemoved", originalText.length() - text.length()
        );
    }
}
