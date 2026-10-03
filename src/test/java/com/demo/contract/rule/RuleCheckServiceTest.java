package com.demo.contract.rule;

import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.rule.domain.RuleResult;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则校验链路集成测试（T-011 / T-012）。
 *
 * <p>覆盖：正文 → 正则抽要素 → 规则引擎 → 落库 → 状态推进。
 *
 * <p>重点断言两件事：
 * <ol>
 *   <li><b>{@code UNDETERMINED} 落库后仍然能与 {@code PASS} 区分</b>——
 *       如果持久化这一层把两者混起来，前面所有的三态设计都白做</li>
 *   <li>重新校验会清掉旧结论，不会让报告混入已经不存在的判断</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class RuleCheckServiceTest extends AuthenticatedTestBase {

    @Autowired
    private ContractService contractService;

    @Autowired
    private ContractParsingService parsingService;

    @Autowired
    private RuleCheckService ruleCheckService;

    @Autowired
    private TestPdfFactory pdfFactory;

    // ==================================================================
    // 正常链路
    // ==================================================================

    @Test
    @DisplayName("完整链路：正文 → 抽要素 → 跑规则 → 落库 → 状态推进")
    void fullRuleCheckShouldPersistFindings() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(wellFormedContract());

        RuleCheckService.CheckSummary summary = ruleCheckService.check(id);

        assertThat(summary.total()).isEqualTo(4);   // 四条规则
        assertThat(summary.hasRuleErrors()).isFalse();
        assertThat(summary.extractedElements())
                .containsEntry("PARTY_A", "北京某某科技有限公司")
                .containsEntry("AMOUNT", "128000.00");

        // 结论已落库，且数量对得上
        assertThat(ruleCheckService.findings(id)).hasSize(4);

        // 状态推进到 RULE_CHECKED
        assertThat(contractService.get(id).getStatus()).isEqualTo(ContractStatus.RULE_CHECKED);
    }

    @Test
    @DisplayName("金额大小写不一致时命中，并落库为 HIT")
    void amountMismatchShouldBeStoredAsHit() throws IOException {
        loginAsDemoTenant();
        // 小写 128000.00，大写写成"壹拾贰万元整"= 120000
        String text = String.join("\n",
                "甲方：北京某某科技有限公司",
                "乙方：上海某某贸易有限公司",
                "合同金额：128000.00元",
                "大写：壹拾贰万元整",
                "付款方式：分两期支付。",
                "违约责任：按日万分之五。",
                "争议解决：提交北京仲裁委员会。");

        Long id = uploadAndParse(text);
        RuleCheckService.CheckSummary summary = ruleCheckService.check(id);

        assertThat(summary.hits()).isGreaterThanOrEqualTo(1);

        var amountFinding = ruleCheckService.findings(id).stream()
                .filter(f -> f.getRuleCode().equals("R-AMOUNT-MISMATCH"))
                .findFirst()
                .orElseThrow();

        assertThat(amountFinding.getResult()).isEqualTo("HIT");
        assertThat(amountFinding.getDetail()).contains("128000").contains("120000");
    }

    // ==================================================================
    // 三态在持久化层不能被混淆
    // ==================================================================

    @Test
    @DisplayName("要素抽不到时落库为 UNDETERMINED，绝不能被存成 PASS")
    void missingElementsMustBeStoredAsUndetermined() throws IOException {
        loginAsDemoTenant();
        // 这份合同里没有甲乙方、没有金额、没有日期，只有条款文字
        String text = "本文只是一段普通文字，付款方式见附件，争议解决另行约定，违约责任另议。";

        Long id = uploadAndParse(text);
        RuleCheckService.CheckSummary summary = ruleCheckService.check(id);

        assertThat(summary.undetermined())
                .withFailMessage("要素缺失却没有产出'无法判定'，说明缺失被当成了通过")
                .isGreaterThan(0);

        // 逐条核对落库结果：金额与日期规则必须是 UNDETERMINED
        var findings = ruleCheckService.findings(id);
        var byCode = findings.stream()
                .collect(java.util.stream.Collectors.toMap(f -> f.getRuleCode(), f -> f));

        assertThat(byCode.get("R-AMOUNT-MISMATCH").getResult()).isEqualTo("UNDETERMINED");
        assertThat(byCode.get("R-DATE-ORDER").getResult()).isEqualTo("UNDETERMINED");
        assertThat(byCode.get("R-PARTY-INCONSISTENT").getResult()).isEqualTo("UNDETERMINED");
        // 必备条款能判断（有正文且三类条款都在）
        assertThat(byCode.get("R-CLAUSE-MISSING").getResult()).isEqualTo("PASS");

        // UNDETERMINED 的条数 ≠ 通过条数
        long undeterminedInDb = findings.stream()
                .filter(f -> f.getResult().equals("UNDETERMINED")).count();
        long passInDb = findings.stream()
                .filter(f -> f.getResult().equals("PASS")).count();
        assertThat(undeterminedInDb).isEqualTo(3);
        assertThat(passInDb).isEqualTo(1);
    }

    @Test
    @DisplayName("无法判定的结论带有具体缺失字段，便于人工定位")
    void undeterminedShouldExplainWhatIsMissing() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse("只有一句话，没有任何要素。");

        ruleCheckService.check(id);

        var amount = ruleCheckService.findings(id).stream()
                .filter(f -> f.getRuleCode().equals("R-AMOUNT-MISMATCH"))
                .findFirst().orElseThrow();

        assertThat(amount.getResult()).isEqualTo("UNDETERMINED");
        assertThat(amount.getDetail())
                .withFailMessage("只说'数据不足'对排查没有帮助：%s", amount.getDetail())
                .contains("AMOUNT");
    }

    // ==================================================================
    // 幂等与确定性
    // ==================================================================

    @Test
    @DisplayName("重复校验清掉旧结论，不会累积")
    void repeatedCheckShouldReplacePreviousFindings() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(wellFormedContract());

        ruleCheckService.check(id);
        assertThat(ruleCheckService.findings(id)).hasSize(4);

        var second = ruleCheckService.check(id);
        assertThat(ruleCheckService.findings(id)).hasSize(4);   // 不是 8
        assertThat(second.removedPreviousFindings()).isEqualTo(4);
    }

    @Test
    @DisplayName("同一输入连续校验两次，结论逐条相同（不变式 I-03）")
    void checkMustBeDeterministic() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(wellFormedContract());

        var first = ruleCheckService.check(id);
        var firstFindings = ruleCheckService.findings(id).stream()
                .map(f -> f.getRuleCode() + "=" + f.getResult()).toList();

        var second = ruleCheckService.check(id);
        var secondFindings = ruleCheckService.findings(id).stream()
                .map(f -> f.getRuleCode() + "=" + f.getResult()).toList();

        assertThat(secondFindings)
                .withFailMessage("同样输入两次校验结论不同，确定性被破坏")
                .isEqualTo(firstFindings);
        assertThat(second.hits()).isEqualTo(first.hits());
        assertThat(second.undetermined()).isEqualTo(first.undetermined());
    }

    // ==================================================================
    // 失败与隔离
    // ==================================================================

    @Test
    @DisplayName("未解析成功的合同不能跑规则校验")
    void unparsedContractCannotBeChecked() throws IOException {
        loginAsDemoTenant();
        byte[] pdf = pdfFactory.build(List.of("Party A: Beijing Demo Technology Co Ltd"));
        Long id = contractService.upload(new MockMultipartFile(
                "file", "x.pdf", "application/pdf", pdf), "未解析合同").contract().id();

        // 刻意不调用 parse：拿空文本跑规则会把"没解析"说成"合同缺条款"
        assertThatThrownBy(() -> ruleCheckService.check(id))
                .isInstanceOf(com.demo.contract.parse.ContractException.class)
                .hasMessageContaining("尚未解析");
    }

    @Test
    @DisplayName("跨租户不能校验也不能读取他人的规则结论")
    void otherTenantShouldBeBlocked() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(wellFormedContract());
        ruleCheckService.check(id);

        loginAsOtherTenant();

        assertThatThrownBy(() -> ruleCheckService.check(id))
                .isInstanceOf(com.demo.contract.parse.ContractException.class);
        assertThat(ruleCheckService.findings(id)).isEmpty();
    }

    @Test
    @DisplayName("删除合同会一并清理规则结论")
    void deletingContractShouldRemoveFindings() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(wellFormedContract());
        ruleCheckService.check(id);
        assertThat(ruleCheckService.findings(id)).isNotEmpty();

        contractService.delete(id);

        assertThat(ruleCheckService.findings(id)).isEmpty();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 一份要素齐全、条款完整的虚构合同，用于让四条规则都能给出确定结论。 */
    private String wellFormedContract() {
        return String.join("\n",
                "采购合同（虚构样例）",
                "甲方：北京某某科技有限公司",
                "乙方：上海某某贸易有限公司",
                "签订日期：2026-01-01",
                "生效日期：2026-01-05",
                "到期日期：2027-01-04",
                "合同金额：128000.00元",
                "大写：壹拾贰万捌仟元整",
                "付款方式：分两期支付。",
                "违约责任：按日万分之五。",
                "争议解决：提交北京仲裁委员会。");
    }

    private Long uploadAndParse(String contractText) throws IOException {
        // 用真实的 PDF 承载这些文字，走完整的上传 + 解析链路
        List<String> lines = contractText.lines().toList();
        byte[] pdf = pdfFactory.build(lines);

        Long id = contractService.upload(new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", pdf), "规则测试合同")
                .contract().id();

        var outcome = parsingService.parse(id);
        assertThat(outcome.success())
                .withFailMessage("测试数据解析失败：%s", outcome.message())
                .isTrue();
        return id;
    }
}
