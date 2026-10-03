package com.demo.contract.rule.support;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 中文大写金额解析器。
 *
 * <p>用途：把"壹拾贰万捌仟元整"转成 {@code 128000}，从而与合同里的小写金额做一致性比对。
 * 这是金额条款最容易出问题的地方——大小写不一致是真实的合同风险
 * （一方按大写执行、另一方按小写执行）。
 *
 * <p><b>刻意只支持到"元"</b>：角、分在实际合同里用得少，而它们的解析规则更琐碎
 * （"壹角伍分"里的 角/分 是十进制细分而不是位值）。
 * 遇到角分时本解析器返回空，让规则走 {@code UNDETERMINED} 而不是猜一个值——
 * <b>宁可转人工，也不要给出一个可能错的金额。</b>
 *
 * <p>支持两种书写风格：
 * <ul>
 *   <li>正式大写：壹贰叁肆伍陆柒捌玖 拾佰仟万亿 元整</li>
 *   <li>简写：一二三四五六七八九 十百千万亿 元</li>
 * </ul>
 */
public final class ChineseAmountParser {

    /** 数字字符 → 数值。同时接受大写与简写。 */
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('O', 0), Map.entry('o', 0),
            Map.entry('一', 1), Map.entry('壹', 1),
            Map.entry('二', 2), Map.entry('贰', 2), Map.entry('两', 2),
            Map.entry('三', 3), Map.entry('叁', 3), Map.entry('参', 3),
            Map.entry('四', 4), Map.entry('肆', 4),
            Map.entry('五', 5), Map.entry('伍', 5),
            Map.entry('六', 6), Map.entry('陆', 6),
            Map.entry('七', 7), Map.entry('柒', 7),
            Map.entry('八', 8), Map.entry('捌', 8),
            Map.entry('九', 9), Map.entry('玖', 9)
    );

    /** 位值：拾/佰/仟 是段内位值，万/亿 是段倍数。 */
    private static final Map<Character, Integer> UNITS = Map.ofEntries(
            Map.entry('拾', 10), Map.entry('十', 10),
            Map.entry('佰', 100), Map.entry('百', 100),
            Map.entry('仟', 1000), Map.entry('千', 1000)
    );

    private static final Map<Character, Long> SECTIONS = Map.of(
            '万', 10_000L,
            '亿', 100_000_000L
    );

    private static final String YUAN = "元";
    private static final String WHOLE = "整";

    private ChineseAmountParser() {
    }

    /**
     * 解析中文大写金额。
     *
     * @param text 例如 "壹拾贰万捌仟元整"、"人民币贰万伍仟元"、"128000"（纯数字也接受）
     * @return 金额；<b>无法可靠解析时返回 {@link java.util.Optional#empty()}</b>，
     *         调用方必须据此判 UNDETERMINED，不得用 0 兜底
     */
    public static java.util.Optional<BigDecimal> parse(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Optional.empty();
        }

        String s = normalize(text);

        // 角/分：本解析器不支持，返回空让对方转人工（放在数字判断之前，避免误判）
        if (s.indexOf('角') >= 0 || s.indexOf('分') >= 0) {
            return java.util.Optional.empty();
        }

        int yuanIdx = s.indexOf(YUAN);
        // 去掉货币符号、"整"、千分位逗号与空格后，得到"数字部分"
        String numberPart = (yuanIdx >= 0 ? s.substring(0, yuanIdx) : s.replace(WHOLE, ""))
                .replace(",", "")
                .replace(" ", "");

        if (numberPart.isEmpty()) {
            return java.util.Optional.empty();
        }

        // 纯阿拉伯数字：直接解析。
        // ⚠️ 这一步必须在去掉逗号之后判断——否则 "1,280,000" 里含逗号，
        //    会被误判成"不是纯数字"，进而落到中文解析分支而失败。
        //    这个顺序我写反过一次，测试直接报"期望能解析出金额，但返回空"。
        if (numberPart.matches("\\d+(\\.\\d+)?")) {
            return java.util.Optional.of(new BigDecimal(numberPart));
        }

        return parseSectionedNumber(numberPart);
    }

    /**
     * 归一化：去掉货币前缀、空白与"整"，并把全角数字转半角。
     *
     * <p>不做的是"把大写转成小写字符"——那会破坏后面的位值解析。
     */
    private static String normalize(String text) {
        String s = text.trim()
                .replace("人民币", "")
                .replace("RMB", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace(" ", "")
                .replace("\u3000", "")
                .replace(",", "");

        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 按"段"解析：亿 / 万 把数字切成若干段，段内按位值累加。
     *
     * <p>算法：
     * <pre>
     * total = 0; section = 0; current = 0
     * 遇到数字 d      → current = d
     * 遇到位值 u      → section += (current == 0 ? 1 : current) * u; current = 0
     *     （current==0 时按 1 处理，是为了 "拾贰" = 12 这种省略前导一的写法）
     * 遇到段倍数 m    → section += current; total += section * m; section = 0; current = 0
     * 结束           → total += section + current
     * </pre>
     */
    private static java.util.Optional<BigDecimal> parseSectionedNumber(String numberPart) {
        long total = 0L;
        long section = 0L;
        int current = 0;
        boolean sawAnyDigit = false;

        for (int i = 0; i < numberPart.length(); i++) {
            char c = numberPart.charAt(i);

            Integer digit = DIGITS.get(c);
            if (digit != null) {
                current = digit;
                if (digit != 0) {
                    sawAnyDigit = true;
                }
                continue;
            }

            Integer unit = UNITS.get(c);
            if (unit != null) {
                // 省略前导一："拾贰" 读作 12
                int base = (current == 0) ? 1 : current;
                section += (long) base * unit;
                current = 0;
                sawAnyDigit = true;
                continue;
            }

            Long mult = SECTIONS.get(c);
            if (mult != null) {
                section += current;
                current = 0;
                // 省略前导一："万" 开头读作 10000
                if (section == 0) {
                    section = 1;
                }
                total += section * mult;
                section = 0;
                sawAnyDigit = true;
                continue;
            }

            // 出现不认识的字符：不猜，直接判无法解析
            return java.util.Optional.empty();
        }

        if (!sawAnyDigit) {
            return java.util.Optional.empty();
        }

        total += section + current;
        return java.util.Optional.of(BigDecimal.valueOf(total));
    }
}
