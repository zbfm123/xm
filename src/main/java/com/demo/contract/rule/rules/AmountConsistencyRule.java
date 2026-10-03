package com.demo.contract.rule.rules;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.Rule;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.Severity;
import com.demo.contract.rule.support.ChineseAmountParser;
import com.demo.contract.rule.support.TextSearch;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 金额一致性：小写金额与大写金额必须相等。
 *
 * <p>为什么这条值得做：大小写金额不一致是真实的合同纠纷来源——
 * 一方按"壹拾贰万"执行、另一方按"128,000"执行，差异金额直接是损失。
 *
 * <p>三态处理是本规则的示范：
 * <ul>
 *   <li>两个金额都抽到且相等 → {@code PASS}</li>
 *   <li>两个金额都抽到但不等 → {@code HIT}，并给出两个值</li>
 *   <li>任一金额未抽到，<b>或大写金额解析不出来</b>（例如含角分）→ {@code UNDETERMINED}</li>
 * </ul>
 *
 * <p>特别注意最后一种：解析不出大写金额时<b>绝不能按 0 处理</b>，
 * 否则会报出一个假的"金额不一致"。
 */
@Component
public class AmountConsistencyRule implements Rule {

    @Override
    public String code() {
        return "R-AMOUNT-MISMATCH";
    }

    @Override
    public String name() {
        return "金额大小写不一致";
    }

    @Override
    public Severity severity() {
        return Severity.HIGH;
    }

    @Override
    public String description() {
        return "比对合同小写金额与大写金额是否一致；大写金额无法解析时转人工确认。";
    }

    @Override
    public RuleOutcome evaluate(RuleContext context) {
        var elements = context.elements();

        // 任一侧缺失即无法判定，并说明缺的是哪个字段
        if (!elements.allUsable(ElementField.AMOUNT, ElementField.AMOUNT_IN_WORDS)) {
            return RuleOutcome.undetermined(
                    "缺少金额要素：" + elements.describeMissing(
                            ElementField.AMOUNT, ElementField.AMOUNT_IN_WORDS));
        }

        BigDecimal lower = elements.getAmount(ElementField.AMOUNT).orElseThrow();
        String wordsRaw = elements.getText(ElementField.AMOUNT_IN_WORDS).orElseThrow();

        Optional<BigDecimal> upperOpt = ChineseAmountParser.parse(wordsRaw);
        if (upperOpt.isEmpty()) {
            // 解析不出来（含角分、写法异常）→ 转人工，不猜
            return RuleOutcome.undetermined(
                    "大写金额无法可靠解析，需人工核对：" + wordsRaw);
        }

        BigDecimal upper = upperOpt.get();

        // 用 compareTo 而不是 equals：128000 与 128000.00 精度不同但金额相同
        if (lower.compareTo(upper) == 0) {
            return RuleOutcome.pass(String.format("小写 %s 与大写 %s 一致", lower.toPlainString(), upper.toPlainString()));
        }

        // 命中：定位两处在文本中的位置，便于人工核对
        Integer start = null;
        Integer end = null;
        Optional<TextSearch.Location> wordsLoc = TextSearch.locate(context.normalizedText(), wordsRaw);
        if (wordsLoc.isPresent()) {
            start = wordsLoc.get().start();
            end = wordsLoc.get().end();
        }

        return RuleOutcome.hit(
                "金额大小写不一致",
                start, end,
                String.format("小写金额=%s，大写金额解析为=%s，差额=%s",
                        lower.toPlainString(), upper.toPlainString(),
                        lower.subtract(upper).abs().toPlainString()));
    }
}
