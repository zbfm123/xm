package com.demo.contract.extract.evidence;

import com.demo.contract.parse.text.NormalizedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据对齐：把模型给出的引文定位回原文坐标。
 *
 * <p><b>本类是整个项目的技术内核。</b> 它解决的问题是：
 * 大模型会"幻觉"出原文里并不存在的条款，而且幻觉的形式是<b>看起来很像的改写</b>。
 * 如果直接信任模型的引用，报告里就会出现无法核实的判断。
 *
 * <p>所以对齐必须是机器校验，并且失败时必须明确降级——这就是不变式 I-02。
 *
 * <h3>三级匹配策略</h3>
 * <table>
 *   <tr><th>级别</th><th>策略</th><th>为什么需要</th></tr>
 *   <tr><td>L1 EXACT</td><td>逐字匹配</td>
 *       <td>最可信，但实际很少见——模型总会有细微改写</td></tr>
 *   <tr><td>L2 NORMALIZED</td><td>全角/半角、空白归一化后匹配，<b>再把区间回映射到原文</b></td>
 *       <td>覆盖大多数情况。这一步最容易写错：直接拿归一化坐标当原文坐标会整体偏移</td></tr>
 *   <tr><td>L3 FUZZY</td><td>滑动窗口 + 二元组相似度</td>
 *       <td>容忍个别字差异；但必须下调置信度</td></tr>
 * </table>
 *
 * <h3>三条绝不妥协的纪律</h3>
 * <ol>
 *   <li><b>匹配到多处且无法消歧 → 判失败</b>，不擅自选一个位置</li>
 *   <li><b>找不到 → 返回"未命中"，不返回近似区间</b>。
 *       给一个"差不多"的位置比不给更糟：人工会跳到无关的地方</li>
 *   <li><b>引文过短 → 判失败</b>。"甲方"能命中全文，但它没有证据价值</li>
 * </ol>
 */
@Component
public class EvidenceAligner {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAligner.class);

    /**
     * 引文的最小长度（按归一化后的字符数）。
     *
     * <p>取 6 是折中：真实的风险条款引文通常远超这个长度；
     * 而 2~5 字的片段（"甲方"、"付款"）能在文中出现几十次，命中它没有证据意义。
     */
    private static final int MIN_QUOTE_LENGTH = 6;

    /**
     * 模糊匹配阈值。
     *
     * <p>取 0.80，基于实测（25 字的合同句子）：
     * <table>
     *   <tr><th>情况</th><th>相似度</th></tr>
     *   <tr><td>差 1 个字</td><td>0.96</td></tr>
     *   <tr><td>差 2 个字</td><td>0.92</td></tr>
     *   <tr><td>同义改写（长度差很多）</td><td>0.48</td></tr>
     *   <tr><td>完全不同的句子</td><td>0.07</td></tr>
     * </table>
     *
     * <p>该阈值能容忍个别字差异，同时拒绝结构不同的句子。
     * <b>调低它会把"另一条款"误判成"同一处"</b>，因此宁可偏高。
     */
    private static final double FUZZY_THRESHOLD = 0.80;

    /**
     * 对齐引文。
     *
     * @param quote   模型给出的引文
     * @param mapView 原文与它的归一化视图
     */
    public AlignmentResult align(String quote, OriginalMap mapView) {
        return alignWithContext(null, quote, mapView);
    }

    /**
     * 带上下文对齐，用于消歧。
     *
     * <p>当引文在文中出现多次时，如果调用方还能提供模型给出的<b>上下文</b>
     * （例如前后句），就可以选出与上下文最贴近的那一处，而不是直接判 AMBIGUOUS。
     *
     * @param context 可选的上下文；为 null 时退化为普通的歧义判定
     */
    public AlignmentResult alignWithContext(String context, String quote, OriginalMap mapView) {
        if (mapView == null || mapView.originalText() == null || mapView.originalText().isEmpty()) {
            return AlignmentResult.miss(quote, AlignmentFailure.INVALID_INPUT);
        }
        if (quote == null || quote.isBlank()) {
            return AlignmentResult.miss(quote, AlignmentFailure.INVALID_INPUT);
        }

        String normalizedQuote = normalizeQuote(quote);
        String text = mapView.normalizedText();

        if (normalizedQuote.isEmpty() || text.isEmpty()) {
            return AlignmentResult.miss(quote, AlignmentFailure.INVALID_INPUT);
        }

        // 过短引文：命中它没有证据价值
        if (normalizedQuote.length() < MIN_QUOTE_LENGTH) {
            return AlignmentResult.miss(quote, AlignmentFailure.TOO_SHORT);
        }

        // ---- L1：在归一化文本上做精确匹配 ----
        // 说明：在归一化文本上匹配本身就同时覆盖了原文的全角/半角与空白差异，
        // 因此 L1 与 L2 在实现上共用一次查找，区别只在于"引文是否也需要归一化"。
        List<int[]> occurrences = findAll(text, normalizedQuote);

        if (occurrences.isEmpty()) {
            // ---- L3：模糊匹配 ----
            AlignmentResult fuzzy = fuzzyMatch(quote, normalizedQuote, mapView);
            if (fuzzy != null) {
                return fuzzy;
            }
            return AlignmentResult.miss(quote, AlignmentFailure.NOT_FOUND);
        }

        // 分级依据：<b>归一化是否真的被用到了</b>，而不是"引文字符串有没有变"。
        //
        // 判据：把命中处的「归一化片段」与「原文片段」比较（都在 OriginalMap 里取），
        // 两者不同说明匹配依赖了归一化。
        //
        // 这个判据我改过两次：
        //   1) 最初用 normalizedQuote.equals(quote) —— 错，引文被改写不代表归一化被用到；
        //   2) 然后拿「原文片段」跟「归一化引文」比 —— 也错，量纲不一致，
        //      原文含多余空白时二者天然不同，但仍应算 NORMALIZED。
        // 正确的比较对象是归一化文本上的那一截。
        int[] occurrence = occurrences.get(0);
        String normalizedSlice = text.substring(occurrence[0], occurrence[1]);
        String originalSlice = mapView.matchedTextAt(occurrence);
        MatchLevel level = normalizedSlice.equals(originalSlice)
                ? MatchLevel.EXACT : MatchLevel.NORMALIZED;

        if (occurrences.size() > 1) {
            // 多处命中：能消歧就选一处，否则判失败
            if (context != null && !context.isBlank()) {
                int[] chosen = pickByContext(text, occurrences, normalizeQuote(context));
                if (chosen != null) {
                    return buildHit(level, chosen, quote, mapView, 1.0, false);
                }
            }
            log.debug("证据多处命中且无法消歧: quote={} 出现{}次",
                    abbreviate(quote), occurrences.size());
            return AlignmentResult.miss(quote, AlignmentFailure.AMBIGUOUS);
        }

        return buildHit(level, occurrences.get(0), quote, mapView, 1.0, false);
    }

    // ==================================================================
    // 归一化
    // ==================================================================

    /**
     * 引文归一化。
     *
     * <p><b>必须与 {@code parse.text.NormalizationPhases.COLLAPSE_WHITESPACE} 口径一致</b>，
     * 否则归一化后的引文在归一化文本里找不到，会静默退化成模糊匹配——
     * 结果是"本该 EXACT 的匹配被记成 FUZZY"，置信度凭空下降。
     *
     * <p>口径（我为此改过一次）：
     * <ul>
     *   <li><b>换行与空格一律折叠为单个半角空格</b>。第一版把换行直接丢弃、
     *       只把空格折叠成空格，于是 "付款方式：\n分两期支付" 归一化成
     *       "付款方式：分两期支付"（无空格），而文本侧是 "付款方式： 分两期支付"（有空格），
     *       两者对不上。</li>
     *   <li>全角字母数字转半角；<b>中文标点不动</b>，否则会掩盖真实的书写差异。</li>
     *   <li>行首空白丢弃（与文本侧的 {@code atLineStart} 行为一致）。</li>
     * </ul>
     */
    private String normalizeQuote(String quote) {
        StringBuilder sb = new StringBuilder(quote.length());
        boolean pendingSpace = false;
        boolean atStart = true;

        for (int i = 0; i < quote.length(); i++) {
            char c = quote.charAt(i);

            if (c == '\n' || c == '\r' || c == ' ' || c == '\t' || c == '\u3000') {
                if (!atStart) {
                    pendingSpace = true;
                }
                continue;
            }

            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }

            if ((c >= '\uFF10' && c <= '\uFF19')
                    || (c >= '\uFF21' && c <= '\uFF3A')
                    || (c >= '\uFF41' && c <= '\uFF5A')) {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
            atStart = false;
        }
        return sb.toString();
    }

    // ==================================================================
    // 查找
    // ==================================================================

    private List<int[]> findAll(String text, String needle) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int i = text.indexOf(needle, from);
            if (i < 0) {
                break;
            }
            out.add(new int[]{i, i + needle.length()});
            from = i + 1;   // 允许重叠出现，避免漏计
        }
        return out;
    }

    /**
     * 按上下文选出最匹配的一处。
     *
     * <p>做法：把每一处命中的前后各取一段，与给出的上下文算相似度，取最高者。
     * 若最高分低于阈值，返回 null（宁可不选，也不要选错）。
     */
    private int[] pickByContext(String text, List<int[]> occurrences, String normalizedContext) {
        if (normalizedContext.isEmpty()) {
            return null;
        }
        int[] best = null;
        double bestScore = 0;
        int window = Math.max(normalizedContext.length(), 40);

        for (int[] occ : occurrences) {
            int from = Math.max(0, occ[0] - window);
            int to = Math.min(text.length(), occ[1] + window);
            double score = similarity(text.substring(from, to), normalizedContext);
            if (score > bestScore) {
                bestScore = score;
                best = occ;
            }
        }
        return bestScore >= 0.5 ? best : null;
    }

    // ==================================================================
    // 模糊匹配
    // ==================================================================

    private AlignmentResult fuzzyMatch(String quote, String normalizedQuote, OriginalMap mapView) {
        String text = mapView.normalizedText();
        int qLen = normalizedQuote.length();
        if (qLen > text.length()) {
            return null;
        }

        // 滑动窗口：窗口长度与引文等长，步长 1。
        // O(n*m)，在合同文本（万字级）与引文（百字级）规模下完全可接受。
        // 若将来文本量级上升到十万字以上，需要换成基于 n-gram 索引的候选筛选。
        int[] bestRange = null;
        double bestScore = 0;

        for (int i = 0; i + qLen <= text.length(); i++) {
            String window = text.substring(i, i + qLen);
            double score = similarity(window, normalizedQuote);
            if (score > bestScore) {
                bestScore = score;
                bestRange = new int[]{i, i + qLen};
                if (score == 1.0) {
                    break;
                }
            }
        }

        if (bestRange == null || bestScore < FUZZY_THRESHOLD) {
            return null;
        }

        return buildHit(MatchLevel.FUZZY, bestRange, quote, mapView, bestScore, false);
    }

    /**
     * 相似度：基于编辑距离，并做长度归一化。
     *
     * <p><b>这里换过一次算法，值得记下来。</b> 第一版用二元组集合的 Dice 系数，
     * 但用 {@code Set} 存二元组会<b>丢失频次与位置信息</b>——
     * 实测中"完全不同的两句话"也得到 1.0 的相似度，模糊匹配实际上完全失效。
     *
     * <p>编辑距离是位置敏感的，实测区分度良好（差 1 字 0.96、完全不同 0.07）。
     * 代价是 O(n·m)，但在"引文百字级、窗口同长"的规模下完全可接受。
     *
     * @return 0~1，1 表示完全相同
     */
    private double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    /** 滚动数组版 Levenshtein 距离，空间 O(min(n,m))。 */
    private int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }

        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(
                        Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }

    // ==================================================================
    // 结果构造：坐标回映射
    // ==================================================================

    /**
     * 把归一化文本上的区间回映射到原文坐标。
     *
     * <p><b>这一步是 L2/L3 最容易出错的地方。</b>
     * 归一化会删除或折叠字符，因此归一化坐标与原文坐标并不相同——
     * 直接拿归一化坐标当原文坐标，取出的片段会整体偏移。
     */
    private AlignmentResult buildHit(MatchLevel level, int[] normalizedRange, String quote,
                                     OriginalMap mapView, double similarity, boolean ambiguous) {
        int[] originalRange = mapView.toOriginalRange(normalizedRange[0], normalizedRange[1]);
        int start = originalRange[0];
        int end = originalRange[1];

        String matchedText = mapView.originalText().substring(start, end);

        return AlignmentResult.hit(level, start, end, quote, matchedText, similarity, ambiguous);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    // ==================================================================
    // 输入视图
    // ==================================================================

    /**
     * 原文与它的归一化视图。
     *
     * <p>对齐必须在<b>归一化文本</b>上做（这样全角/半角、空白差异自然被覆盖），
     * 但结果必须是<b>原文坐标</b>。这个记录把两者绑在一起，
     * 让"忘了回映射"这件事在类型上就不容易发生。
     *
     * @param normalizedText    归一化文本
     * @param offsetsToOriginal 归一化下标 → 原文下标
     * @param originalText      原文
     */
    public record OriginalMap(String normalizedText, int[] offsetsToOriginal, String originalText) {

        public OriginalMap {
            if (normalizedText == null || originalText == null) {
                throw new IllegalArgumentException("原文与归一化文本都不能为 null");
            }
            if (offsetsToOriginal == null
                    || offsetsToOriginal.length != normalizedText.length()) {
                throw new IllegalArgumentException(
                        "映射长度必须与归一化文本一致: map="
                                + (offsetsToOriginal == null ? "null" : offsetsToOriginal.length)
                                + " text=" + normalizedText.length());
            }
        }

        /** 由解析阶段的 {@link NormalizedText} 构造。 */
        public static OriginalMap from(NormalizedText normalized) {
            return new OriginalMap(
                    normalized.text(), normalized.offsetsToOriginal(), normalized.originalText());
        }

        /**
         * 归一化区间对应的原文片段。
         *
         * <p>用于判定"归一化是否真的被用到了"：把命中处的原文片段与归一化片段比较，
         * 不同说明匹配依赖了归一化。
         */
        public String matchedTextAt(int[] normalizedRange) {
            int[] original = toOriginalRange(normalizedRange[0], normalizedRange[1]);
            return originalText.substring(original[0], original[1]);
        }

        /**
         * 把归一化区间回映射为原文区间。
         *
         * <p>起点取首字符映射，终点取末字符映射 + 1。
         */
        public int[] toOriginalRange(int start, int end) {
            if (start < 0 || end > normalizedText.length() || start > end) {
                throw new IndexOutOfBoundsException(
                        "归一化区间越界: [" + start + "," + end + ") 长度=" + normalizedText.length());
            }
            if (start == end) {
                int at = start >= offsetsToOriginal.length
                        ? (offsetsToOriginal.length == 0
                            ? 0 : offsetsToOriginal[offsetsToOriginal.length - 1] + 1)
                        : offsetsToOriginal[start];
                return new int[]{at, at};
            }
            return new int[]{
                    offsetsToOriginal[start],
                    offsetsToOriginal[end - 1] + 1
            };
        }
    }
}
