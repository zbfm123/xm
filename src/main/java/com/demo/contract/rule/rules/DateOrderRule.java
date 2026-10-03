package com.demo.contract.rule.rules;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.Rule;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.Severity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 日期逻辑：签署日 ≤ 生效日 ≤ 到期日。
 *
 * <p>这条规则体现"时间必须注入"的价值：它需要比较日期，但<b>不需要"今天是几号"</b>。
 * 因此实现里完全不读系统时钟，测试可以直接固定日期。
 * （{@code today} 在 {@code RuleContext} 里，留给"是否已过期"这类规则用。）
 *
 * <p>只比较能确定顺序的日期对：
 * <ul>
 *   <li>签署日 &gt; 生效日 → 不合理（未签先生效）</li>
 *   <li>生效日 &gt; 到期日 → 不合理（生效即过期）</li>
 *   <li>签署日 &gt; 到期日 → 不合理</li>
 * </ul>
 *
 * <p>缺失的日期<b>不参与判断，但也不因此放行</b>：
 * 只要有两对可比较的日期就能得出结论；若可用日期不足两个，
 * 则返回 {@code UNDETERMINED}。
 */
@Component
public class DateOrderRule implements Rule {

    @Override
    public String code() {
        return "R-DATE-ORDER";
    }

    @Override
    public String name() {
        return "日期先后顺序矛盾";
    }

    @Override
    public Severity severity() {
        return Severity.HIGH;
    }

    @Override
    public String description() {
        return "检查签署日、生效日、到期日之间的先后顺序是否合理。";
    }

    @Override
    public RuleOutcome evaluate(RuleContext context) {
        var elements = context.elements();

        LocalDate sign = elements.getDate(ElementField.SIGN_DATE).orElse(null);
        LocalDate effective = elements.getDate(ElementField.EFFECTIVE_DATE).orElse(null);
        LocalDate expiry = elements.getDate(ElementField.EXPIRY_DATE).orElse(null);

        int available = (sign != null ? 1 : 0) + (effective != null ? 1 : 0) + (expiry != null ? 1 : 0);
        if (available < 2) {
            return RuleOutcome.undetermined("可比较的日期不足两个：" + elements.describeMissing(
                    ElementField.SIGN_DATE, ElementField.EFFECTIVE_DATE, ElementField.EXPIRY_DATE));
        }

        List<String> problems = new ArrayList<>();
        if (sign != null && effective != null && sign.isAfter(effective)) {
            problems.add(String.format("签署日(%s)晚于生效日(%s)", sign, effective));
        }
        if (effective != null && expiry != null && effective.isAfter(expiry)) {
            problems.add(String.format("生效日(%s)晚于到期日(%s)", effective, expiry));
        }
        if (sign != null && expiry != null && sign.isAfter(expiry)) {
            problems.add(String.format("签署日(%s)晚于到期日(%s)", sign, expiry));
        }

        if (problems.isEmpty()) {
            // 说明比较了哪几对，便于人工确认"这条结论覆盖了什么"
            return RuleOutcome.pass("已比较的日期对无矛盾：" + describeCompared(sign, effective, expiry));
        }
        return RuleOutcome.hit("日期顺序矛盾", null, null, String.join("；", problems));
    }

    private String describeCompared(LocalDate sign, LocalDate effective, LocalDate expiry) {
        List<String> pairs = new ArrayList<>();
        if (sign != null && effective != null) {
            pairs.add("签署日/生效日");
        }
        if (effective != null && expiry != null) {
            pairs.add("生效日/到期日");
        }
        if (sign != null && expiry != null) {
            pairs.add("签署日/到期日");
        }
        return String.join("、", pairs);
    }
}
