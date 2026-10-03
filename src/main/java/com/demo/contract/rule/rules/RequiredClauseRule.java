package com.demo.contract.rule.rules;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.Rule;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.Severity;
import com.demo.contract.rule.support.TextSearch;
import org.springframework.stereotype.Component;

/**
 * 必备条款缺失：合同正文里应当能查到关键条款。
 *
 * <p>这是本模块里唯一需要读正文的规则，因此它同时体现了两件事：
 * <ol>
 *   <li>规则可以读文本，但文本是 {@code RuleContext} 的一部分，不额外访问数据库</li>
 *   <li><b>关键字匹配是启发式的</b>——只能判断"文中出现了相关字样"，
 *       不能判断"条款内容是否有效"。这一点的局限必须如实说明，
 *       不能让用户以为"系统确认了争议解决条款没问题"。</li>
 * </ol>
 *
 * <p>三态：文本为空 → {@code UNDETERMINED}（没有正文就无法判断，
 * 而不是"没有必备条款"）。这两者的区别正是本模块的核心设计。
 */
@Component
public class RequiredClauseRule implements Rule {

    /**
     * 必备条款的关键字表。
     *
     * <p>写法上考虑了两点：
     * <ul>
     *   <li>同义表达并列（"争议解决"/"仲裁"/"诉讼"），避免漏判</li>
     *   <li><b>宁可多列</b>：漏判（该有却报没有）会让人不再信任工具，
     *       误判（有却报缺失）只是多一次人工确认。方向是"宁松勿严"。</li>
     * </ul>
     */
    private static final String CLAUSE_DISPUTE = "争议解决";
    private static final String CLAUSE_PAYMENT = "付款";
    private static final String CLAUSE_BREACH = "违约";

    @Override
    public String code() {
        return "R-CLAUSE-MISSING";
    }

    @Override
    public String name() {
        return "必备条款缺失";
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public String description() {
        return "检查争议解决、付款、违约三类关键条款是否在正文中出现；"
                + "仅判断关键字是否出现，不判断条款内容是否有效。";
    }

    @Override
    public RuleOutcome evaluate(RuleContext context) {
        String text = context.normalizedText();
        if (text == null || text.isBlank()) {
            // 没有正文 ≠ 没有条款。没有正文是"无法判断"
            return RuleOutcome.undetermined("无可用正文，无法判断条款是否存在");
        }

        java.util.List<String> missing = new java.util.ArrayList<>();
        Integer firstStart = null;
        Integer firstEnd = null;
        String firstKeyword = null;

        var dispute = TextSearch.locateAny(text, java.util.List.of(
                CLAUSE_DISPUTE, "仲裁", "诉讼", "管辖"));
        if (dispute.isEmpty()) {
            missing.add("争议解决");
        } else if (firstKeyword == null) {
            firstKeyword = dispute.get().keyword();
            firstStart = dispute.get().location().start();
            firstEnd = dispute.get().location().end();
        }

        var payment = TextSearch.locateAny(text, java.util.List.of(
                CLAUSE_PAYMENT, "支付", "结算", "价款"));
        if (payment.isEmpty()) {
            missing.add("付款");
        }

        var breach = TextSearch.locateAny(text, java.util.List.of(
                CLAUSE_BREACH, "违约责任", "赔偿责任"));
        if (breach.isEmpty()) {
            missing.add("违约");
        }

        if (missing.isEmpty()) {
            return RuleOutcome.pass("争议解决、付款、违约三类条款均可检索到");
        }

        // 命中：指向第一个能定位到的相关位置，便于人工快速跳转
        return RuleOutcome.hit(
                "缺少关键条款：" + String.join("、", missing),
                firstStart, firstEnd,
                "缺失清单：" + String.join("、", missing)
                        + "。注意：本规则只判断关键字是否出现，不判断条款内容是否有效。");
    }
}
