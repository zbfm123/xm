package com.demo.contract.parse.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本归一化的分步实现。
 *
 * <p>核心约束：<b>每一步都必须维护「新文本下标 → 原文下标」的映射</b>。
 * 这是 T-009 里最容易写错的地方，所以拆成独立的 {@link Phase} 逐个实现、逐个测试，
 * 而不是写成一个多分支的大循环。
 *
 * <p>每一步的语义都在类注释里写清"删了什么、怎么保映射"，
 * 因为事后从代码反推意图的成本远高于写下来。
 */
final class NormalizationPhases {

    private NormalizationPhases() {
    }

    /**
     * 一个归一化步骤：接收「文本 + 映射」，返回新的一对。
     *
     * @param text    当前文本
     * @param toOrigin 当前文本每个下标对应的原文下标，长度与 text 相同
     */
    interface Phase {
        Result apply(String text, int[] toOrigin);
    }

    record Result(String text, int[] toOrigin) {
    }

    static Result run(String text, int[] toOrigin, Phase phase) {
        Result r = phase.apply(text, toOrigin);
        if (r.text().length() != r.toOrigin().length) {
            throw new IllegalStateException(
                    "归一化步骤破坏了映射：text=" + r.text().length() + " map=" + r.toOrigin().length);
        }
        return r;
    }

    // ==================================================================
    // Phase A：统一换行符
    // ==================================================================

    /** 把 {@code \r\n} 与 {@code \r} 统一为 {@code \n}；换行符映射到 {@code \r} 的原下标。 */
    static final Phase UNIFY_NEWLINES = (text, toOrigin) -> {
        StringBuilder sb = new StringBuilder(text.length());
        List<Integer> map = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                sb.append('\n');
                map.add(toOrigin[i]);
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;      // 跳过 \r\n 的 \n，避免产生两个换行
                }
            } else {
                sb.append(c);
                map.add(toOrigin[i]);
            }
        }
        return new Result(sb.toString(), toArray(map));
    };

    // ==================================================================
    // Phase B：空白折叠
    // ==================================================================

    /**
     * 折叠空白：
     * <ul>
     *   <li>行内连续空白（空格/制表符/全角空格）折叠为单个半角空格</li>
     *   <li>行尾空白删除</li>
     *   <li><b>换行本身也按空白处理，折叠为单个空格</b>，不保留 {@code \n}</li>
     * </ul>
     *
     * <p>⚠️ <b>最后一行为什么重要</b>：最初版本保留换行、只删行尾空白，
     * 结果是 {@code "付款方式：\n分两期"} 归一化后仍是 {@code "付款方式：\n分两期"}
     * （冒号后没有空格），而下游大模型给出的引文通常是
     * {@code "付款方式： 分两期"}（冒号后有空格）。两者对不上，
     * <b>本该精确命中的证据被迫退化成模糊匹配，置信度凭空下降</b>。
     *
     * <p>把换行也折叠成空格后，文本与引文的空白形态一致，
     * 精确匹配的命中率显著提高。代价是丢失了原始的分行结构——
     * 本项目按"条款/段落"切分时用的是别的手段，不依赖 {@code \n}。
     *
     * <p>映射规则：折叠后的字符映射到<b>被折叠序列的第一个字符</b>的原下标。
     */
    static final Phase COLLAPSE_WHITESPACE = (text, toOrigin) -> {
        StringBuilder sb = new StringBuilder(text.length());
        List<Integer> map = new ArrayList<>(text.length());

        boolean pendingSpace = false;
        int pendingSpaceOrigin = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int origin = toOrigin[i];

            // 换行与其它空白同等对待：折叠为一个空格，行首不产生空格
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t' || c == '\u3000') {
                if (sb.length() > 0 && !pendingSpace) {
                    pendingSpace = true;
                    pendingSpaceOrigin = origin;
                }
                continue;
            }

            if (pendingSpace) {
                sb.append(' ');
                map.add(pendingSpaceOrigin);
                pendingSpace = false;
            }

            sb.append(c);
            map.add(origin);
        }

        return new Result(sb.toString(), toArray(map));
    };

    // ==================================================================
    // Phase C：全角转半角
    // ==================================================================

    /**
     * 全角转半角，<b>只转换全角字母与数字</b>：
     * <ul>
     *   <li>全角数字 {@code ０-９} → {@code 0-9}</li>
     *   <li>全角大写 {@code Ａ-Ｚ} → {@code A-Z}</li>
     *   <li>全角小写 {@code ａ-ｚ} → {@code a-z}</li>
     *   <li>全角空格 {@code U+3000} → 半角空格</li>
     * </ul>
     *
     * <p>⚠️ <b>刻意不用 {@code U+FF01..U+FF5E} 整段平移</b>。那个区间里混着大量中文标点：
     * {@code ，。、；：！？（）＂＇} 等，它们的全角码位同样落在 {@code FF01-FF5E}。
     * 整段减 {@code 0xFEE0} 会把中文标点变成半角，破坏中文合同的可读性，
     * 也会让基于标点的关键字匹配失效。
     *
     * <p>这个坑我踩过一次：注释里写着"不转中文标点"，代码却因为用了整段平移而全转了，
     * 测试立刻报出 {@code 金额：} 变成 {@code 金额:}。<b>注释和代码不一致时，测试是唯一可靠的一方。</b>
     */
    static final Phase FULLWIDTH_TO_HALFWIDTH = (text, toOrigin) -> {
        StringBuilder sb = new StringBuilder(text.length());
        List<Integer> map = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char converted = c;
            if ((c >= '\uFF10' && c <= '\uFF19')       // ０-９
                    || (c >= '\uFF21' && c <= '\uFF3A')  // Ａ-Ｚ
                    || (c >= '\uFF41' && c <= '\uFF5A')) { // ａ-ｚ
                converted = (char) (c - 0xFEE0);
            } else if (c == '\u3000') {
                converted = ' ';
            }
            sb.append(converted);
            map.add(toOrigin[i]);
        }
        return new Result(sb.toString(), toArray(map));
    };

    // ==================================================================
    // Phase D：去页眉页脚
    // ==================================================================

    /**
     * 去掉重复出现的页眉/页脚行。
     *
     * <p>判定方式：把文本按换行切分，统计去掉首尾空白后每一行出现的次数；
     * 出现次数 ≥ {@code minRepeat} 且长度 <b>严格小于</b> {@code maxLength} 的行视为页眉页脚。
     *
     * <p>两道护栏，避免误删正文：
     * <ol>
     *   <li>只在文档被分成 ≥ {@code minRepeat} 页时才启用（单页文档没有页眉页脚可言）</li>
     *   <li>长度上限且取严格小于：长句重复更像是合同里的标准条款</li>
     * </ol>
     *
     * <p><b>这是一条启发式规则，不是精确判断。</b> 两类情况都会"放过"：
     * <ul>
     *   <li>递增页码（{@code 第 1 页} / {@code 第 2 页}）每次都不同，重复行判定抓不到</li>
     *   <li>页面绝对位置目前没有被利用——真正的页眉页脚只出现在页首/页尾，
     *       用位置信息能显著提高准确率。这留作后续改进。</li>
     * </ul>
     *
     * <p>刻意的取舍：<b>宁可留下页眉，也不要误删正文。</b>
     * 留下的页眉人一眼能忽略，删掉的正文没人能找回来。
     */
    static Phase removeRepeatedLines(int pageCount, int minRepeat, int maxLength) {
        return (text, toOrigin) -> {
            if (pageCount < minRepeat) {
                return new Result(text, toOrigin);
            }

            List<int[]> lineRanges = splitLines(text);

            // 统计每行（去首尾空白后）出现次数
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (int[] range : lineRanges) {
                String key = text.substring(range[0], range[1]).trim();
                if (isHeaderCandidate(key, maxLength)) {
                    counts.merge(key, 1, Integer::sum);
                }
            }

            // 先决定保留哪些行，再重建文本。
            //
            // ⚠️ 不要"边遍历边删除"：那样会把行与行之间的换行符一起丢掉，
            //    导致被删行两侧的正文粘连成一段
            //    （曾经产生 "第一页正文内容：…第 1 页第二页正文内容" 这种症状）。
            //    先选行、后用 '\n' 连接，换行只可能少在"被删行自己的那一行"上。
            List<int[]> kept = new ArrayList<>();
            int dropped = 0;
            for (int[] range : lineRanges) {
                String raw = text.substring(range[0], range[1]);
                String key = raw.trim();
                boolean drop = isHeaderCandidate(key, maxLength)
                        && counts.getOrDefault(key, 0) >= minRepeat;
                if (drop) {
                    dropped++;
                } else {
                    kept.add(range);
                }
            }

            StringBuilder sb = new StringBuilder(text.length());
            List<Integer> map = new ArrayList<>(text.length());
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) {
                    // 行间换行：映射到该行起点，保证区间回查落在原文内
                    sb.append('\n');
                    map.add(toOrigin[kept.get(i)[0]]);
                }
                int[] range = kept.get(i);
                for (int p = range[0]; p < range[1]; p++) {
                    sb.append(text.charAt(p));
                    map.add(toOrigin[p]);
                }
            }

            Result result = new Result(sb.toString(), toArray(map));
            // 用折叠空白收拾掉删除产生的多余空行
            return run(result.text(), result.toOrigin(), COLLAPSE_WHITESPACE);
        };
    }

    /**
     * 该行是否有资格被当作页眉页脚。
     *
     * <p>长度用<b>严格小于</b>：正好等于上限的行不处理。
     * 边界上的行更可能是正文条款，保守处理。
     */
    private static boolean isHeaderCandidate(String trimmedLine, int maxLength) {
        return !trimmedLine.isEmpty() && trimmedLine.length() < maxLength;
    }

    /** 按换行切分，返回每行的 {@code [start, end)}（不含换行符本身）。 */
    private static List<int[]> splitLines(String text) {
        List<int[]> ranges = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                ranges.add(new int[]{start, i});
                start = i + 1;
            }
        }
        if (start <= text.length()) {
            ranges.add(new int[]{start, text.length()});
        }
        return ranges;
    }

    static int[] toArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
