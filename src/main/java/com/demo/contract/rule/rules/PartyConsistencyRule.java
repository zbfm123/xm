package com.demo.contract.rule.rules;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.Rule;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.Severity;
import com.demo.contract.rule.support.TextSearch;
import org.springframework.stereotype.Component;

/**
 * 主体名称不一致：甲乙方名称应当能在正文里找到。
 *
 * <p>这条规则的价值在于发现"抽到的名称与正文对不上"——
 * 常见原因是抽取把附件里的另一个主体抽成了甲方，或者正文里写的是简称。
 *
 * <p>三态：
 * <ul>
 *   <li>甲、乙都未抽到 → {@code UNDETERMINED}</li>
 *   <li>抽到了但正文里找不到 → {@code HIT}（这是值得人工看的信息）</li>
 *   <li>找不到的原因是"正文用了简称" → 仍然 {@code HIT}，
 *       因为<b>名称不一致本身就是需要确认的风险</b>，不是系统的问题</li>
 * </ul>
 */
@Component
public class PartyConsistencyRule implements Rule {

    @Override
    public String code() {
        return "R-PARTY-INCONSISTENT";
    }

    @Override
    public String name() {
        return "主体名称与正文不符";
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public String description() {
        return "检查抽取到的甲乙方名称能否在正文中检索到；找不到说明名称可能来自附件或使用了简称。";
    }

    @Override
    public RuleOutcome evaluate(RuleContext context) {
        var elements = context.elements();
        String text = context.normalizedText();

        var partyA = elements.getText(ElementField.PARTY_A).orElse(null);
        var partyB = elements.getText(ElementField.PARTY_B).orElse(null);

        if (partyA == null && partyB == null) {
            return RuleOutcome.undetermined("甲乙方名称均未抽到：" + elements.describeMissing(
                    ElementField.PARTY_A, ElementField.PARTY_B));
        }

        if (text == null || text.isBlank()) {
            return RuleOutcome.undetermined("无可用正文，无法核对主体名称");
        }

        java.util.List<String> notFound = new java.util.ArrayList<>();
        Integer start = null;
        Integer end = null;

        if (partyA != null) {
            var loc = TextSearch.locate(text, partyA);
            if (loc.isEmpty()) {
                notFound.add("甲方「" + partyA + "」");
            } else {
                start = loc.get().start();
                end = loc.get().end();
            }
        }
        if (partyB != null) {
            var loc = TextSearch.locate(text, partyB);
            if (loc.isEmpty()) {
                notFound.add("乙方「" + partyB + "」");
            } else if (start == null) {
                start = loc.get().start();
                end = loc.get().end();
            }
        }

        if (notFound.isEmpty()) {
            return RuleOutcome.pass("甲乙方名称均可在正文中检索到");
        }
        return RuleOutcome.hit(
                "主体名称在正文中检索不到：" + String.join("、", notFound),
                start, end,
                "可能原因：名称来自附件、正文使用简称，或抽取串到了其他文件。需人工确认。");
    }
}
