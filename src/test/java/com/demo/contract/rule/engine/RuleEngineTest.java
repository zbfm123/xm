package com.demo.contract.rule.engine;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.Rule;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.RuleResult;
import com.demo.contract.rule.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则引擎测试。
 *
 * <p>重点不是"规则能跑"，而是三态语义与出错处理：
 * <ul>
 *   <li>{@code UNDETERMINED} 绝不能被当成 {@code PASS}</li>
 *   <li>一条规则出错不能让整体失效，但必须显式可见</li>
 *   <li>同输入两次执行结果逐条一致</li>
 * </ul>
 */
class RuleEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    private RuleContext context() {
        return new RuleContext(
                MapElementLookup.builder().defaultUnknown(ElementField.values()),
                "虚构合同正文", TODAY);
    }

    // ==================================================================
    // 三态
    // ==================================================================

    @Test
    @DisplayName("三种结果都能被正确识别并统计")
    void engineShouldDistinguishThreeStates() {
        RuleEngine engine = engineOf(
                new FixedRule("R-A", RuleOutcome.hit("命中", 0, 2)),
                new FixedRule("R-B", RuleOutcome.pass()),
                new FixedRule("R-C", RuleOutcome.undetermined("AMOUNT(UNKNOWN)")));

        var result = engine.execute(context());

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.hitCount()).isEqualTo(1);
        assertThat(result.passCount()).isEqualTo(1);
        assertThat(result.undeterminedCount()).isEqualTo(1);
        assertThat(result.hasRuleErrors()).isFalse();
    }

    @Test
    @DisplayName("UNDETERMINED 不能被算作 PASS —— 这是本模块的核心不变量")
    void undeterminedMustNotBeCountedAsPass() {
        RuleEngine engine = engineOf(
                new FixedRule("R-C", RuleOutcome.undetermined("AMOUNT(UNKNOWN)")));

        var result = engine.execute(context());

        // 用最直白的方式断言：无法判定的条数不为 0，且通过条数不为 1
        assertThat(result.undeterminedCount())
                .withFailMessage("无法判定的结论被折叠进了'通过'，会导致信息不足时输出'合规'")
                .isEqualTo(1);
        assertThat(result.passCount()).isZero();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("结果里保留缺失字段的具体说明，不能只说'数据不足'")
    void undeterminedShouldCarrySpecificReason() {
        RuleEngine engine = engineOf(
                new FixedRule("R-C", RuleOutcome.undetermined("AMOUNT(UNKNOWN)、SIGN_DATE(UNKNOWN)")));

        var finding = engine.execute(context()).undetermined().get(0);

        assertThat(finding.detail()).contains("AMOUNT").contains("SIGN_DATE");
    }

    // ==================================================================
    // 出错处理
    // ==================================================================

    @Test
    @DisplayName("一条规则抛异常不影响其他规则")
    void oneRuleFailureMustNotBreakOthers() {
        RuleEngine engine = engineOf(
                new FixedRule("R-A", RuleOutcome.hit("命中", null, null)),
                new ThrowingRule("R-B"),
                new FixedRule("R-C", RuleOutcome.pass()));

        var result = engine.execute(context());

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.hitCount()).isEqualTo(1);
        assertThat(result.hasRuleErrors()).isTrue();
        assertThat(result.errorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("出错的规则必须显式可见，且不能表现成'通过'")
    void failedRuleMustBeVisibleAndNotLookLikePass() {
        RuleEngine engine = engineOf(new ThrowingRule("R-B"));

        var finding = engine.execute(context()).findings().get(0);

        assertThat(finding.hasError()).isTrue();
        assertThat(finding.errorMessage()).isNotBlank();
        // 出错意味着"没有产出"，因此是三态里的无法判定，绝不是通过
        assertThat(finding.result())
                .withFailMessage("规则出错被标成了 %s，会让用户以为'这条没问题'", finding.result())
                .isEqualTo(RuleResult.UNDETERMINED);
        assertThat(finding.isHit()).isFalse();
    }

    @Test
    @DisplayName("规则返回 null 视为实现缺陷，同样转成出错结论而不是崩溃")
    void nullOutcomeShouldBeTreatedAsError() {
        RuleEngine engine = engineOf(new NullReturningRule("R-N"));

        var result = engine.execute(context());

        assertThat(result.hasRuleErrors()).isTrue();
        assertThat(result.findings().get(0).result()).isEqualTo(RuleResult.UNDETERMINED);
    }

    // ==================================================================
    // 确定性
    // ==================================================================

    @Test
    @DisplayName("同一输入连续执行两次，结果逐条完全相同（不变式 I-03）")
    void executionMustBeDeterministic() {
        RuleEngine engine = engineOf(
                new FixedRule("R-Z", RuleOutcome.hit("z", 1, 2)),
                new FixedRule("R-A", RuleOutcome.pass()),
                new FixedRule("R-M", RuleOutcome.undetermined("X(UNKNOWN)")));

        var first = engine.execute(context());
        var second = engine.execute(context());

        assertThat(second.findings()).isEqualTo(first.findings());
    }

    @Test
    @DisplayName("输出按规则编码排序，顺序稳定")
    void outputOrderShouldBeStable() {
        RuleEngine engine = engineOf(
                new FixedRule("R-Z", RuleOutcome.pass()),
                new FixedRule("R-A", RuleOutcome.pass()),
                new FixedRule("R-M", RuleOutcome.pass()));

        assertThat(engine.execute(context()).findings())
                .extracting(f -> f.ruleCode())
                .containsExactly("R-A", "R-M", "R-Z");
    }

    // ==================================================================
    // 注册表
    // ==================================================================

    @Test
    @DisplayName("规则编码重复时启动即失败，而不是运行时结论混在一起")
    void duplicateRuleCodeShouldFailFast() {
        assertThatThrownBy(() -> new RuleRegistry(List.of(
                new FixedRule("R-SAME", RuleOutcome.pass()),
                new FixedRule("R-SAME", RuleOutcome.hit("dup", null, null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("规则编码重复")
                .hasMessageContaining("R-SAME");
    }

    @Test
    @DisplayName("规则编码为空时启动即失败")
    void blankRuleCodeShouldFailFast() {
        assertThatThrownBy(() -> new RuleRegistry(List.of(new FixedRule("  ", RuleOutcome.pass()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能为空");
    }

    // ==================================================================
    // 区间校验
    // ==================================================================

    @Test
    @DisplayName("区间起止颠倒时立即失败，不产出非法区间")
    void invalidRangeShouldFailFast() {
        assertThatThrownBy(() -> RuleOutcome.hit("x", 10, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("区间非法");
    }

    private RuleEngine engineOf(Rule... rules) {
        return new RuleEngine(new RuleRegistry(List.of(rules)));
    }

    // ==================================================================
    // 测试替身
    // ==================================================================

    /** 固定返回某个结果的规则。 */
    private static class FixedRule implements Rule {
        private final String code;
        private final RuleOutcome outcome;

        FixedRule(String code, RuleOutcome outcome) {
            this.code = code;
            this.outcome = outcome;
        }

        @Override public String code() { return code; }
        @Override public String name() { return "测试规则 " + code; }
        @Override public Severity severity() { return Severity.MEDIUM; }
        @Override public String description() { return "测试用"; }
        @Override public RuleOutcome evaluate(RuleContext context) { return outcome; }
    }

    private static class ThrowingRule extends FixedRule {
        ThrowingRule(String code) {
            super(code, RuleOutcome.pass());
        }

        @Override
        public RuleOutcome evaluate(RuleContext context) {
            throw new IllegalStateException("模拟规则实现缺陷");
        }
    }

    private static class NullReturningRule extends FixedRule {
        NullReturningRule(String code) {
            super(code, RuleOutcome.pass());
        }

        @Override
        public RuleOutcome evaluate(RuleContext context) {
            return null;
        }
    }
}
