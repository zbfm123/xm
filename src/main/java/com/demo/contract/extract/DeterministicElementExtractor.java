package com.demo.contract.extract;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.ElementStatus;
import com.demo.contract.rule.engine.MapElementLookup;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性要素抽取（纯正则，不调用任何模型）。
 *
 * <p>为什么先把正则版本做出来：金额、日期、金额大写这些字段<b>格式强、可正则</b>，
 * 用正则的收益是零成本、零延迟、完全可复现；
 * 模型的价值在于发现正则漏掉的表达方式，而不是替代正则。
 *
 * <p>两者冲突时怎么办，本类已经预留了位置：{@link #extract} 返回的结果里可以把字段
 * 标成 {@link ElementStatus#CONFLICT}，让规则走"无法判定"而不是替人挑一个。
 * <b>冲突本身就是需要人看的信息。</b>
 *
 * <p>⚠️ 当前实现只做正则，因此对非常规写法会抽不到 —— 那种情况应当是
 * {@code UNKNOWN} 而不是猜一个值。这一条由 {@link MapElementLookup} 的语义保证。
 */
@Component
public class DeterministicElementExtractor {

    /**
     * 小写金额。
     *
     * <p><b>必须锚定在金额关键词之后</b>，不能只靠"一串数字"。
     * 我第一版就是纯数字匹配，结果在 "签订日期：2026-01-01" 里把 <b>2026 当成了合同金额</b>。
     * 上下文关键词才是区分"金额"与"日期"的关键——这与日期抽取必须在
     * "签订日期"之后取数字是同一个道理。
     *
     * <p>兼容：128,000.00 / 128000 元 / ￥128000 / 128000.00元 / 人民币128000
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:合同金额|合同总价|合同价款|价款|总金额|总价|金额|合计|人民币)\\s*[:：]?\\s*"
                    + "(?:RMB|￥|¥)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(万元|元|块)?");

    /** 兜底：带货币单位但没有金额关键词的写法，例如 "共计 128000.00 元"。 */
    private static final Pattern AMOUNT_FALLBACK = Pattern.compile(
            "(?:RMB|￥|¥)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    /** 大写金额：从"元"往前抓，覆盖常见字符集。 */
    private static final Pattern AMOUNT_IN_WORDS = Pattern.compile(
            "([零〇一二三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟万亿元整]{2,})元(整)?");

    /**
     * 甲乙方名称。
     *
     * <p>⚠️ <b>取值部分必须用"非空白字符"（{@code \S}）而不是"非换行字符"</b>。
     *
     * <p>这里踩过一个坑：归一化会把换行折叠成空格，因此文本里<b>已经没有换行可依赖</b>。
     * 第一版用 {@code [^\n；;，,]{2,40}} 取值，结果在"甲方：北京某某科技有限公司
     * 乙方：上海某某贸易有限公司 签订日期：…"这种单行文本里，
     * 贪婪地吞掉了后面几十个字：抽出来的甲方变成了
     * {@code "北京某某科技有限公司 乙方：上海某某贸易有限公司 签订日期：2026-01-01"}。
     *
     * <p>改用 {@code \S} 后，遇到第一个空格即停止。
     * 公司名中间的空格会截断名称——这是<b>刻意的取舍</b>：
     * 截断的名称仍能在正文中命中（主体一致性规则会通过），
     * 而贪婪匹配产出的超长"名称"根本不可能命中，会稳定误报。
     */
    private static final Pattern PARTY_A = Pattern.compile(
            "甲\\s*方\\s*[（(]?[^)）\\s]{0,20}[)）]?\\s*[:：]\\s*([\\S；;，,]{2,40})");

    private static final Pattern PARTY_B = Pattern.compile(
            "乙\\s*方\\s*[（(]?[^)）\\s]{0,20}[)）]?\\s*[:：]\\s*([\\S；;，,]{2,40})");

    private static final Pattern SIGN_DATE = Pattern.compile(
            "签[订署]\\s*日[期]?\\s*[:：]?\\s*(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})");

    private static final Pattern EFFECTIVE_DATE = Pattern.compile(
            "(?:生效|开始)日[期]?\\s*[:：]?\\s*(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})");

    private static final Pattern EXPIRY_DATE = Pattern.compile(
            "(?:到期|终止|届满)日[期]?\\s*[:：]?\\s*(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})");

    /**
     * 从归一化文本抽取要素。
     *
     * <p>返回的查找表只登记<b>抽到或明确无值</b>的字段；
     * 规则里用 {@code allUsable} 做前置检查，未登记的字段视为 UNKNOWN。
     */
    public ElementExtraction extract(String text) {
        MapElementLookup lookup = MapElementLookup.builder();
        Map<String, String> summary = new LinkedHashMap<>();

        if (text == null || text.isBlank()) {
            lookup.defaultUnknown(ElementField.values());
            summary.put("note", "无正文");
            return new ElementExtraction(lookup, summary);
        }

        extractAmount(text, lookup, summary);
        extractAmountInWords(text, lookup, summary);
        extractParties(text, lookup, summary);
        extractDates(text, lookup, summary);

        // 未抽到的字段显式标 UNKNOWN —— "没抽到"必须是显式状态，不能是"没登记"
        lookup.defaultUnknown(ElementField.values());

        return new ElementExtraction(lookup, summary);
    }

    private void extractAmount(String text, MapElementLookup lookup, Map<String, String> summary) {
        // 先按金额关键词找
        Optional<AmountHit> hit = tryAmount(AMOUNT, text, true);
        if (hit.isEmpty()) {
            // 再退回"带货币符号"的写法
            hit = tryAmount(AMOUNT_FALLBACK, text, false);
        }

        if (hit.isPresent()) {
            AmountHit h = hit.get();
            lookup.amount(ElementField.AMOUNT, h.value(), ElementStatus.CONFIRMED);
            lookup.range(ElementField.AMOUNT, h.start(), h.end());
            summary.put("AMOUNT", h.value().toPlainString());
        } else {
            lookup.unknown(ElementField.AMOUNT);
            summary.put("AMOUNT", "未抽到");
        }
    }

    /**
     * 尝试用给定正则抽取金额。
     *
     * @param hasUnitGroup 该正则是否带"万元/元"单位分组；带单位时要按万元换算
     */
    private Optional<AmountHit> tryAmount(Pattern pattern, String text, boolean hasUnitGroup) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String raw = m.group(1).replace(",", "");
            try {
                BigDecimal value = new BigDecimal(raw);

                // 单位换算："8.5万元" = 85000
                if (hasUnitGroup && m.groupCount() >= 2 && "万元".equals(m.group(2))) {
                    value = value.multiply(BigDecimal.valueOf(10_000));
                }

                // 过滤掉看起来像年份的数字：1900~2099 的纯四位整数
                // （金额关键词已经把我们引到金额附近，这里再挡一道，
                //   避免 "合同金额：2026年度框架" 这种写法把年份当金额）
                if (raw.length() == 4 && value.compareTo(new BigDecimal("1900")) >= 0
                        && value.compareTo(new BigDecimal("2099")) <= 0) {
                    continue;
                }
                return Optional.of(new AmountHit(value, m.start(1), m.end(1)));
            } catch (NumberFormatException ignored) {
                // 不是合法数字就继续找下一个
            }
        }
        return Optional.empty();
    }

    private record AmountHit(BigDecimal value, int start, int end) {
    }

    private void extractAmountInWords(String text, MapElementLookup lookup, Map<String, String> summary) {
        Matcher m = AMOUNT_IN_WORDS.matcher(text);
        if (m.find()) {
            // 把"元"补回去，保持与合同原文一致，便于回查定位
            String words = m.group(0);
            lookup.text(ElementField.AMOUNT_IN_WORDS, words, ElementStatus.CONFIRMED);
            lookup.range(ElementField.AMOUNT_IN_WORDS, m.start(), m.end());
            summary.put("AMOUNT_IN_WORDS", words);
            return;
        }
        lookup.unknown(ElementField.AMOUNT_IN_WORDS);
        summary.put("AMOUNT_IN_WORDS", "未抽到");
    }

    private void extractParties(String text, MapElementLookup lookup, Map<String, String> summary) {
        putIfFound(text, PARTY_A, lookup, ElementField.PARTY_A, summary);
        putIfFound(text, PARTY_B, lookup, ElementField.PARTY_B, summary);
    }

    private void putIfFound(String text, Pattern pattern, MapElementLookup lookup,
                            ElementField field, Map<String, String> summary) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String value = m.group(1).trim();
            if (!value.isEmpty()) {
                lookup.text(field, value, ElementStatus.CONFIRMED);
                lookup.range(field, m.start(1), m.end(1));
                summary.put(field.name(), value);
                return;
            }
        }
        lookup.unknown(field);
        summary.put(field.name(), "未抽到");
    }

    private void extractDates(String text, MapElementLookup lookup, Map<String, String> summary) {
        putDate(text, SIGN_DATE, lookup, ElementField.SIGN_DATE, summary);
        putDate(text, EFFECTIVE_DATE, lookup, ElementField.EFFECTIVE_DATE, summary);
        putDate(text, EXPIRY_DATE, lookup, ElementField.EXPIRY_DATE, summary);
    }

    private void putDate(String text, Pattern pattern, MapElementLookup lookup,
                         ElementField field, Map<String, String> summary) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
                lookup.date(field, date, ElementStatus.CONFIRMED);
                lookup.range(field, m.start(1), m.end(3));
                summary.put(field.name(), date.format(DateTimeFormatter.ISO_LOCAL_DATE));
                return;
            } catch (RuntimeException e) {
                // 日期数字非法（例如 2026-13-45）：不猜，标为冲突交由人工
                lookup.conflict(field);
                summary.put(field.name(), "日期非法，标为冲突");
                return;
            }
        }
        lookup.unknown(field);
        summary.put(field.name(), "未抽到");
    }

    /**
     * 抽取结果。
     *
     * @param lookup  交给规则引擎的要素表
     * @param summary 人可读摘要，便于日志与演示
     */
    public record ElementExtraction(MapElementLookup lookup, Map<String, String> summary) {

        public Optional<String> get(String field) {
            return Optional.ofNullable(summary.get(field));
        }

        public int extractedCount() {
            return (int) summary.values().stream()
                    .filter(v -> !"未抽到".equals(v))
                    .count();
        }
    }
}
