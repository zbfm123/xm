package com.demo.contract.rule.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文大写金额解析测试。
 *
 * <p>这是本项目里最"脏"的一段逻辑：中文数字有多种写法（大写/简写、省略前导一、零的省略），
 * 而且<b>解析失败必须返回空</b>而不是猜一个值——否则会报出假的"金额不一致"。
 * 因此正例与"必须失败"的例子都要覆盖。
 */
class ChineseAmountParserTest {

    @Test
    @DisplayName("正式大写：壹拾贰万捌仟元整 = 128000")
    void shouldParseFormalUpperAmount() {
        assertThat(parse("壹拾贰万捌仟元整")).isEqualByComparingTo("128000");
    }

    @Test
    @DisplayName("简写：十二万八千元 = 128000")
    void shouldParseSimplifiedWording() {
        assertThat(parse("十二万八千元")).isEqualByComparingTo("128000");
    }

    @Test
    @DisplayName("省略前导一：拾万 = 100000，万 = 10000")
    void shouldHandleOmittedLeadingOne() {
        assertThat(parse("拾万元整")).isEqualByComparingTo("100000");
        assertThat(parse("万元")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("带货币前缀与分隔符也能解析")
    void shouldStripCurrencyPrefixAndSeparators() {
        assertThat(parse("人民币壹万元整")).isEqualByComparingTo("10000");
        assertThat(parse("￥1,280,000元")).isEqualByComparingTo("1280000");
    }

    @Test
    @DisplayName("纯阿拉伯数字也可解析（大写栏被填成数字的情况）")
    void shouldParsePlainDigits() {
        assertThat(parse("128000")).isEqualByComparingTo("128000");
        assertThat(parse("128000.50")).isEqualByComparingTo("128000.50");
    }

    @Test
    @DisplayName("亿级别：壹亿贰仟万元 = 120000000")
    void shouldParseHundredMillion() {
        assertThat(parse("壹亿贰仟万元整")).isEqualByComparingTo("120000000");
    }

    @Test
    @DisplayName("零的处理：壹仟零伍元 = 1005")
    void shouldHandleZeros() {
        assertThat(parse("壹仟零伍元整")).isEqualByComparingTo("1005");
        assertThat(parse("壹万零伍拾元整")).isEqualByComparingTo("10050");
    }

    @Test
    @DisplayName("个位数：伍元 = 5")
    void shouldParseSingleDigit() {
        assertThat(parse("伍元整")).isEqualByComparingTo("5");
    }

    // ==================================================================
    // 必须判"无法解析"的情况——不能猜
    // ==================================================================

    @Test
    @DisplayName("含角分时返回空，转人工而不是猜一个金额")
    void shouldRefuseAmountWithJiaoAndFen() {
        // 角/分是十进制细分而非位值，规则与本解析器不同。
        // 宁可让规则判 UNDETERMINED，也不要算错。
        assertThat(ChineseAmountParser.parse("壹佰元伍角叁分")).isEmpty();
        assertThat(ChineseAmountParser.parse("壹拾元零伍分")).isEmpty();
    }

    @Test
    @DisplayName("空值、空白、纯符号返回空")
    void shouldReturnEmptyForBlankInput() {
        assertThat(ChineseAmountParser.parse(null)).isEmpty();
        assertThat(ChineseAmountParser.parse("")).isEmpty();
        assertThat(ChineseAmountParser.parse("   ")).isEmpty();
        assertThat(ChineseAmountParser.parse("人民币元整")).isEmpty();
    }

    @Test
    @DisplayName("含无法识别的字符时返回空，而不是忽略它继续算")
    void shouldReturnEmptyForUnknownCharacters() {
        // "壹佰X元" 里的 X 不认识：忽略它会把 100 当成正确答案，这是危险的
        assertThat(ChineseAmountParser.parse("壹佰X元整")).isEmpty();
        assertThat(ChineseAmountParser.parse("金额待定")).isEmpty();
    }

    @Test
    @DisplayName("解析结果用 compareTo 比较，不受精度写法影响")
    void precisionShouldNotMatterWhenComparing() {
        BigDecimal parsed = ChineseAmountParser.parse("壹万元整").orElseThrow();
        // 128000 与 128000.00 是不同的 BigDecimal，但金额相同
        assertThat(parsed.compareTo(new BigDecimal("10000.00"))).isZero();
    }

    private BigDecimal parse(String text) {
        Optional<BigDecimal> r = ChineseAmountParser.parse(text);
        assertThat(r)
                .withFailMessage("期望能解析出金额，但返回空：%s", text)
                .isPresent();
        return r.get();
    }
}
