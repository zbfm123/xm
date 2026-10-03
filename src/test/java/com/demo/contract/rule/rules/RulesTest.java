package com.demo.contract.rule.rules;

import com.demo.contract.rule.domain.ElementField;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.RuleResult;
import com.demo.contract.rule.engine.MapElementLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四条规则的行为测试。
 *
 * <p>每个规则都重点覆盖<b>无法判定</b>的分支：那是最容易被写成"通过"的地方，
 * 也是本模块三态设计的核心价值。
 */
class RulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    private static RuleContext ctx(MapElementLookup elements, String text) {
        return new RuleContext(elements, text, TODAY);
    }

    // ==================================================================
    @Nested
    @DisplayName("金额一致性 R-AMOUNT-MISMATCH")
    class AmountRule {

        private final AmountConsistencyRule rule = new AmountConsistencyRule();

        @Test
        @DisplayName("大小写一致 → PASS")
        void consistentShouldPass() {
            var e = MapElementLookup.builder()
                    .amount(ElementField.AMOUNT, "128000")
                    .text(ElementField.AMOUNT_IN_WORDS, "壹拾贰万捌仟元整");
            assertThat(rule.evaluate(ctx(e, "")).result()).isEqualTo(RuleResult.PASS);
        }

        @Test
        @DisplayName("大小写不一致 → HIT，且给出两个值与差额")
        void mismatchShouldHit() {
            var e = MapElementLookup.builder()
                    .amount(ElementField.AMOUNT, "128000")
                    .text(ElementField.AMOUNT_IN_WORDS, "壹拾贰万元整");
            RuleOutcome out = rule.evaluate(ctx(e, "壹拾贰万元整"));

            assertThat(out.result()).isEqualTo(RuleResult.HIT);
            assertThat(out.detail()).contains("128000").contains("120000").contains("8000");
        }

        @Test
        @DisplayName("缺少任一侧 → UNDETERMINED，并指出缺哪个字段")
        void missingAmountShouldBeUndetermined() {
            var onlyLower = MapElementLookup.builder()
                    .amount(ElementField.AMOUNT, "128000")
                    .unknown(ElementField.AMOUNT_IN_WORDS);
            RuleOutcome out = rule.evaluate(ctx(onlyLower, ""));

            assertThat(out.result()).isEqualTo(RuleResult.UNDETERMINED);
            assertThat(out.detail()).contains("AMOUNT_IN_WORDS");
        }

        @Test
        @DisplayName("大写含角分无法解析 → UNDETERMINED，绝不按 0 处理")
        void unparsableWordsShouldBeUndeterminedNotHit() {
            var e = MapElementLookup.builder()
                    .amount(ElementField.AMOUNT, "100")
                    .text(ElementField.AMOUNT_IN_WORDS, "壹佰元伍角叁分");
            RuleOutcome out = rule.evaluate(ctx(e, ""));

            // 如果这里实现成"解析失败=0"，就会报出假的金额不一致
            assertThat(out.result())
                    .withFailMessage("大写金额解析失败被当成了不一致，会产出假警报：%s", out.detail())
                    .isEqualTo(RuleResult.UNDETERMINED);
        }

        @Test
        @DisplayName("金额要素来源冲突 → UNDETERMINED")
        void conflictShouldBeUndetermined() {
            var e = MapElementLookup.builder()
                    .conflict(ElementField.AMOUNT)
                    .text(ElementField.AMOUNT_IN_WORDS, "壹万元整");
            assertThat(rule.evaluate(ctx(e, "")).result()).isEqualTo(RuleResult.UNDETERMINED);
        }

        @Test
        @DisplayName("命中时带上大写金额在原文中的准确区间")
        void hitShouldCarryLocationWhenFound() {
            // 前缀 "合同金额：" 正好 5 个字符，因此大写金额从下标 5 开始
            String text = "合同金额：壹拾贰万元整（小写 128000 元）";
            var e = MapElementLookup.builder()
                    .amount(ElementField.AMOUNT, "128000")
                    .text(ElementField.AMOUNT_IN_WORDS, "壹拾贰万元整");
            RuleOutcome out = rule.evaluate(ctx(e, text));

            assertThat(out.result()).isEqualTo(RuleResult.HIT);
            assertThat(out.hasLocation()).isTrue();
            assertThat(out.charStart()).isEqualTo(5);
            assertThat(text.substring(out.charStart(), out.charEnd())).isEqualTo("壹拾贰万元整");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("日期顺序 R-DATE-ORDER")
    class DateRule {

        private final DateOrderRule rule = new DateOrderRule();

        @Test
        @DisplayName("签署 ≤ 生效 ≤ 到期 → PASS")
        void correctOrderShouldPass() {
            var e = MapElementLookup.builder()
                    .date(ElementField.SIGN_DATE, "2026-01-01")
                    .date(ElementField.EFFECTIVE_DATE, "2026-01-05")
                    .date(ElementField.EXPIRY_DATE, "2027-01-04");
            assertThat(rule.evaluate(ctx(e, "")).result()).isEqualTo(RuleResult.PASS);
        }

        @Test
        @DisplayName("签署日晚于生效日 → HIT")
        void signAfterEffectiveShouldHit() {
            var e = MapElementLookup.builder()
                    .date(ElementField.SIGN_DATE, "2026-02-01")
                    .date(ElementField.EFFECTIVE_DATE, "2026-01-01");
            RuleOutcome out = rule.evaluate(ctx(e, ""));

            assertThat(out.result()).isEqualTo(RuleResult.HIT);
            assertThat(out.detail()).contains("签署日").contains("生效日");
        }

        @Test
        @DisplayName("生效日晚于到期日 → HIT（生效即过期）")
        void effectiveAfterExpiryShouldHit() {
            var e = MapElementLookup.builder()
                    .date(ElementField.EFFECTIVE_DATE, "2026-06-01")
                    .date(ElementField.EXPIRY_DATE, "2026-01-01");
            assertThat(rule.evaluate(ctx(e, "")).result()).isEqualTo(RuleResult.HIT);
        }

        @Test
        @DisplayName("可比较日期不足两个 → UNDETERMINED")
        void insufficientDatesShouldBeUndetermined() {
            var e = MapElementLookup.builder()
                    .date(ElementField.SIGN_DATE, "2026-01-01")
                    .unknown(ElementField.EFFECTIVE_DATE)
                    .unknown(ElementField.EXPIRY_DATE);
            RuleOutcome out = rule.evaluate(ctx(e, ""));

            assertThat(out.result()).isEqualTo(RuleResult.UNDETERMINED);
            assertThat(out.detail()).contains("EFFECTIVE_DATE");
        }

        @Test
        @DisplayName("PASS 时说明比较了哪几对日期，避免'结论覆盖了什么'不清楚")
        void passShouldExplainWhatWasCompared() {
            var e = MapElementLookup.builder()
                    .date(ElementField.SIGN_DATE, "2026-01-01")
                    .date(ElementField.EFFECTIVE_DATE, "2026-01-05")
                    .unknown(ElementField.EXPIRY_DATE);
            RuleOutcome out = rule.evaluate(ctx(e, ""));

            assertThat(out.result()).isEqualTo(RuleResult.PASS);
            assertThat(out.detail()).contains("签署日/生效日");
            assertThat(out.detail()).doesNotContain("到期日");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("必备条款 R-CLAUSE-MISSING")
    class ClauseRule {

        private final RequiredClauseRule rule = new RequiredClauseRule();

        @Test
        @DisplayName("三类条款都能检索到 → PASS")
        void allClausesPresentShouldPass() {
            String text = "第一条 付款方式：分两期支付。第二条 违约责任：按日万分之五。"
                    + "第三条 争议解决：提交北京仲裁委员会。";
            assertThat(rule.evaluate(ctx(MapElementLookup.builder(), text)).result())
                    .isEqualTo(RuleResult.PASS);
        }

        @Test
        @DisplayName("缺争议解决条款 → HIT，并在清单里列出")
        void missingDisputeClauseShouldHit() {
            String text = "第一条 付款方式：分两期支付。第二条 违约责任：按日万分之五。";
            RuleOutcome out = rule.evaluate(ctx(MapElementLookup.builder(), text));

            assertThat(out.result()).isEqualTo(RuleResult.HIT);
            assertThat(out.evidence()).contains("争议解决");
        }

        @Test
        @DisplayName("同义表达也能识别：诉讼 / 管辖 / 仲裁")
        void synonymsShouldBeRecognized() {
            String text = "因本合同发生纠纷，由甲方所在地人民法院管辖。付款方式见附件。违约责任另议。";
            assertThat(rule.evaluate(ctx(MapElementLookup.builder(), text)).result())
                    .isEqualTo(RuleResult.PASS);
        }

        @Test
        @DisplayName("无正文 → UNDETERMINED，而不是'缺少必备条款'")
        void emptyTextShouldBeUndeterminedNotHit() {
            RuleOutcome out = rule.evaluate(ctx(MapElementLookup.builder(), ""));

            // "没有正文"与"正文里没有该条款"是两件事
            assertThat(out.result())
                    .withFailMessage("无正文被判成了命中，会把'系统没读到'说成'合同缺条款'")
                    .isEqualTo(RuleResult.UNDETERMINED);
        }

        @Test
        @DisplayName("缺少的条款没有位置可指——此时区间为空是正确行为")
        void missingClauseHasNoLocation() {
            String text = "付款方式：见附件。违约责任：另议。";
            RuleOutcome out = rule.evaluate(ctx(MapElementLookup.builder(), text));

            assertThat(out.result()).isEqualTo(RuleResult.HIT);
            // "争议解决"在文中根本不存在，因此不可能给出位置。
            // 如果这里硬塞一个位置（例如指向第一次命中的其他关键字），
            // 人工点击跳转时会落到无关的地方 —— 那比不给位置更糟。
            assertThat(out.hasLocation())
                    .withFailMessage("缺失的条款不该有位置：%s", out.charStart())
                    .isFalse();
        }

        @Test
        @DisplayName("缺失清单里列出全部缺项，而不只是第一个")
        void shouldListAllMissingClauses() {
            RuleOutcome out = rule.evaluate(ctx(MapElementLookup.builder(), "本文只有一句话。"));

            assertThat(out.detail()).contains("争议解决").contains("付款").contains("违约");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("主体一致性 R-PARTY-INCONSISTENT")
    class PartyRule {

        private final PartyConsistencyRule rule = new PartyConsistencyRule();

        @Test
        @DisplayName("甲乙方名称都能在正文中找到 → PASS")
        void bothPartiesFoundShouldPass() {
            String text = "甲方：北京某某科技有限公司；乙方：上海某某贸易有限公司。";
            var e = MapElementLookup.builder()
                    .text(ElementField.PARTY_A, "北京某某科技有限公司")
                    .text(ElementField.PARTY_B, "上海某某贸易有限公司");
            assertThat(rule.evaluate(ctx(e, text)).result()).isEqualTo(RuleResult.PASS);
        }

        @Test
        @DisplayName("名称在正文中找不到 → HIT，并说明可能原因")
        void missingPartyShouldHit() {
            String text = "甲方：某某公司；乙方：另一家公司。";
            var e = MapElementLookup.builder()
                    .text(ElementField.PARTY_A, "北京某某科技有限公司")
                    .text(ElementField.PARTY_B, "另一家公司");
            RuleOutcome out = rule.evaluate(ctx(e, text));

            assertThat(out.result()).isEqualTo(RuleResult.HIT);
            assertThat(out.evidence()).contains("北京某某科技有限公司");
            assertThat(out.detail()).contains("简称");
        }

        @Test
        @DisplayName("甲乙方都未抽到 → UNDETERMINED")
        void noPartiesShouldBeUndetermined() {
            var e = MapElementLookup.builder()
                    .unknown(ElementField.PARTY_A)
                    .unknown(ElementField.PARTY_B);
            RuleOutcome out = rule.evaluate(ctx(e, "正文"));

            assertThat(out.result()).isEqualTo(RuleResult.UNDETERMINED);
            assertThat(out.detail()).contains("PARTY_A").contains("PARTY_B");
        }

        @Test
        @DisplayName("有名称但无正文 → UNDETERMINED")
        void noTextShouldBeUndetermined() {
            var e = MapElementLookup.builder().text(ElementField.PARTY_A, "甲公司");
            assertThat(rule.evaluate(ctx(e, "")).result()).isEqualTo(RuleResult.UNDETERMINED);
        }
    }
}
