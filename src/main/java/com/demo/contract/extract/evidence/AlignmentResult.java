package com.demo.contract.extract.evidence;

/**
 * 证据对齐结果。
 *
 * <p><b>本记录的核心约束：未命中时不得携带任何位置。</b>
 * 返回一个"差不多接近"的区间，比不返回区间更糟——人工复核会点击跳转到
 * 无关的地方，然后以为系统定位错了地方，而不是以为定位失败了。
 *
 * @param matched  是否命中
 * @param level    匹配级别（未命中时为 {@link MatchLevel#NONE}）
 * @param start    原文区间起点（仅命中时有意义）
 * @param end      原文区间终点，开区间（仅命中时有意义）
 * @param quote    模型给出的原始引文
 * @param matchedText 原文中实际命中的文本片段，供人工核对差异
 * @param similarity 相似度 0~1（EXACT/NORMALIZED 为 1.0）
 * @param ambiguous 该引文在原文中出现多次
 * @param failure  失败原因（仅未命中时有值）
 */
public record AlignmentResult(
        boolean matched,
        MatchLevel level,
        Integer start,
        Integer end,
        String quote,
        String matchedText,
        double similarity,
        boolean ambiguous,
        AlignmentFailure failure
) {

    public AlignmentResult {
        if (matched && (start == null || end == null)) {
            throw new IllegalArgumentException("命中时必须给出原文区间");
        }
        if (matched && level == MatchLevel.NONE) {
            throw new IllegalArgumentException("命中时级别不能是 NONE");
        }
        if (!matched && (start != null || end != null)) {
            throw new IllegalArgumentException(
                    "未命中时不得携带位置：给出一个'差不多'的区间会误导人工复核");
        }
        if (!matched && failure == null) {
            throw new IllegalArgumentException("未命中时必须给出失败原因");
        }
    }

    public static AlignmentResult hit(MatchLevel level, int start, int end,
                                      String quote, String matchedText,
                                      double similarity, boolean ambiguous) {
        return new AlignmentResult(true, level, start, end, quote, matchedText,
                similarity, ambiguous, null);
    }

    /** 未命中：刻意不接受任何位置参数。 */
    public static AlignmentResult miss(String quote, AlignmentFailure failure) {
        return new AlignmentResult(false, MatchLevel.NONE, null, null, quote,
                null, 0.0, false, failure);
    }

    /** 该级别对应的置信度权重，供综合评分使用。 */
    public double levelWeight() {
        return level.weight();
    }

    public int length() {
        return matched ? end - start : 0;
    }

    @Override
    public String toString() {
        if (!matched) {
            return "Evidence{未命中, reason=" + failure + ", quote='" + abbreviate(quote) + "'}";
        }
        return "Evidence{" + level + ", [" + start + "," + end + "), 相似度="
                + String.format("%.3f", similarity) + (ambiguous ? ", 多处命中" : "")
                + ", quote='" + abbreviate(quote) + "'}";
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 20 ? s : s.substring(0, 20) + "…";
    }
}
