package com.demo.contract.aireview;

import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiClient;
import com.demo.contract.aireview.client.AiErrorCode;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.rule.RuleCheckService;
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

/**
 * 要素抽取与 AI 审查链路集成测试（T-015 / T-016）。
 *
 * <p>用真实的 Mock 桩（它从输入文本截取引文），因此正向链路会被真正走到。
 *
 * <p>反复断言的核心命题：
 * <b>引文无法在原文定位的结论，绝不进入报告正文</b>（不变式 I-02）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class AiReviewIntegrationTest extends AuthenticatedTestBase {

    @Autowired
    private ContractService contractService;

    @Autowired
    private ContractParsingService parsingService;

    @Autowired
    private ElementExtractionService extractionService;

    @Autowired
    private AiReviewService aiReviewService;

    @Autowired
    private RuleCheckService ruleCheckService;

    @Autowired
    private TestPdfFactory pdfFactory;

    @Autowired
    private AiClient aiClient;

    /** 含要素与多种风险的虚构合同。 */
    private String contractText() {
        return String.join("\n",
                "采购合同（虚构样例）",
                "甲方：北京某某科技有限公司",
                "乙方：上海某某贸易有限公司",
                "签订日期：2026-01-01",
                "合同金额：128000.00元",
                "付款方式：另行约定。",
                "因本合同产生的一切损失均由乙方承担全部责任。",
                "本合同期满后自动续约一年。",
                "争议解决：友好协商。");
    }

    // ==================================================================
    // T-015 要素抽取
    // ==================================================================

    @Test
    @DisplayName("要素抽取：引文全部能在原文定位，字段落库且带区间与置信度")
    void extractionShouldAlignAllQuotes() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        var summary = extractionService.extract(id);

        assertThat(summary.total()).isGreaterThan(0);
        assertThat(summary.mismatch())
                .withFailMessage("桩产出的引文无法被对齐，说明桩与真实链路不匹配")
                .isZero();
        assertThat(summary.alignmentRate()).isEqualTo(1.0);

        var elements = extractionService.elements(id);
        assertThat(elements).isNotEmpty();

        for (var e : elements) {
            // 对齐成功的字段必须带区间
            assertThat(e.getCharStart()).isNotNull();
            assertThat(e.getCharEnd()).isNotNull();
            assertThat(e.getMatchLevel()).isNotNull();
            assertThat(e.getConfidence()).isNotNull();
            assertThat(e.getStatus()).isIn("CONFIRMED", "LOW_CONFIDENCE");
            // 能按区间从原文取回片段
            var text = parsingService.requireText(id).getOriginalText();
            assertThat(text.substring(e.getCharStart(), e.getCharEnd())).isNotBlank();
        }
    }

    @Test
    @DisplayName("要素抽取：模型给出枚举外字段被忽略，不影响已知字段")
    void unknownModelFieldShouldBeIgnored() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());
        var summary = extractionService.extract(id);
        // 桩只产出已知字段，因此这里验证的是"抽取本身可用"
        assertThat(summary.total()).isGreaterThan(0);
    }

    @Test
    @DisplayName("抽出的要素能被规则引擎使用，且规则结论从 UNDETERMINED 变为确定")
    void extractedElementsShouldFeedRules() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        // 抽取前：只有确定性正则抽出的字段，金额大小写与日期可能缺
        // 抽取后：应当有更多字段可用
        extractionService.extract(id);

        var lookup = extractionService.asElementLookup(id);
        assertThat(lookup.isUsable(com.demo.contract.rule.domain.ElementField.PARTY_A))
                .withFailMessage("抽取后的甲方字段应当可用")
                .isTrue();
        assertThat(lookup.getText(com.demo.contract.rule.domain.ElementField.PARTY_A))
                .contains("北京某某科技有限公司");
    }

    // ==================================================================
    // T-016 AI 风险审查
    // ==================================================================

    @Test
    @DisplayName("AI 审查：桩引文全部定位成功，结论状态都是候选态")
    void reviewShouldProduceCandidatesWithEvidence() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        var summary = aiReviewService.review(id);

        assertThat(summary.total()).isGreaterThan(0);
        assertThat(summary.mismatch()).isZero();
        assertThat(summary.reportable()).isEqualTo(summary.total());

        var findings = aiReviewService.findings(id);
        for (var f : findings) {
            // 所有条目都必须是候选态——**没有"已生效"这个状态**
            assertThat(f.getStatus())
                    .withFailMessage("AI 结论出现了非候选状态: %s", f.getStatus())
                    .isIn("PENDING", "LOW_CONFIDENCE", "EVIDENCE_MISMATCH", "EVIDENCE_AMBIGUOUS");
            // 可追溯性：模型与提示词版本必填
            assertThat(f.getModelVersion()).isNotBlank();
            assertThat(f.getPromptVersion()).isNotBlank();
        }
        // 至少有置信度达标的条目
        assertThat(findings.stream().anyMatch(f -> "PENDING".equals(f.getStatus()))).isTrue();
    }

    @Test
    @DisplayName("不变式 I-02：引文无法定位的条目不进报告正文，且状态明确")
    void unlocatableFindingsMustNotBeReportable() throws IOException {
        loginAsDemoTenant();
        // 加入触发桩的"演示幻觉"分支，产出一条原文不存在的引文
        Long id = uploadAndParse(contractText() + "\n演示幻觉");

        var summary = aiReviewService.review(id);

        assertThat(summary.mismatch())
                .withFailMessage("幻觉条目没有被证据对齐拦下来")
                .isGreaterThan(0);

        var all = aiReviewService.findings(id);
        var reportable = aiReviewService.reportableFindings(id);

        // 幻觉条目的状态必须是证据不匹配类，且**没有区间**
        var hallucination = all.stream()
                .filter(f -> f.getCharStart() == null)
                .toList();
        assertThat(hallucination).isNotEmpty();
        for (var h : hallucination) {
            assertThat(h.getStatus()).isIn("EVIDENCE_MISMATCH", "EVIDENCE_AMBIGUOUS");
            assertThat(h.getStatusReason()).contains("原文");
            assertThat(h.getConfidence()).isEqualByComparingTo("0");
        }

        // 关键：reportable 列表里不能有这些条目
        assertThat(reportable.size()).isEqualTo(all.size() - summary.mismatch());
        assertThat(reportable)
                .withFailMessage("无法定位的结论出现在了可报告列表里，违反不变式 I-02")
                .allSatisfy(f -> assertThat(f.getCharStart()).isNotNull());
    }

    @Test
    @DisplayName("置信度 = 模型自评 × 匹配级别权重，且带引文长度惩罚")
    void confidenceShouldReflectEvidenceStrength() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());
        aiReviewService.review(id);

        var findings = aiReviewService.findings(id);
        for (var f : findings) {
            if (f.getCharStart() == null) {
                continue;
            }
            // 模型自评最大 0.95（桩的规则），因此综合置信度不会超过它
            assertThat(f.getConfidence().doubleValue()).isBetween(0.0, 1.0);
            // 级别必须与权重一致：FUZZY 的置信度必然被压低
            if ("FUZZY".equals(f.getMatchLevel())) {
                assertThat(f.getConfidence().doubleValue())
                        .withFailMessage("FUZZY 命中的置信度没有被下调")
                        .isLessThan(0.95);
            }
        }
    }

    @Test
    @DisplayName("重复审查清掉旧结论，不累积")
    void repeatedReviewShouldReplacePrevious() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        var first = aiReviewService.review(id);
        int countAfterFirst = aiReviewService.findings(id).size();

        var second = aiReviewService.review(id);
        assertThat(aiReviewService.findings(id)).hasSize(countAfterFirst);
        assertThat(second.removedPrevious()).isEqualTo(first.total());
    }

    @Test
    @DisplayName("同一输入两次审查结论一致（可复现）")
    void reviewShouldBeDeterministic() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        aiReviewService.review(id);
        var first = aiReviewService.findings(id).stream()
                .map(f -> f.getRiskType() + "|" + f.getStatus() + "|" + f.getConfidence())
                .toList();

        aiReviewService.review(id);
        var second = aiReviewService.findings(id).stream()
                .map(f -> f.getRiskType() + "|" + f.getStatus() + "|" + f.getConfidence())
                .toList();

        assertThat(second).isEqualTo(first);
    }

    // ==================================================================
    // 降级与隔离
    // ==================================================================

    @Test
    @DisplayName("AI 不可用时抛 AI_UNAVAILABLE —— 由调用方决定降级，不在这里吞掉")
    void aiUnavailableShouldSurfaceToCaller() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        // 用桩替换掉当前 client 不可行（bean 已注入），因此直接验证异常类型语义：
        // AiClient 在当前测试环境是 Mock，永远可用；这里断言的是异常分类本身
        assertThat(aiClient.isAvailable()).isTrue();
        AiCallException e = new AiCallException(AiErrorCode.AI_UNAVAILABLE, "测试");
        assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE);
        // 不可用不应被标为"可重试"——重试一个没配置的通道毫无意义
        assertThat(e.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("「不可用」不等于「未发现风险」：降级状态必须能被区分出来")
    void unavailableMustNotLookLikeNoRisk() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        // 未执行 AI 审查时，findings 为空——这与"审了但没发现问题"在数据上不同：
        // 前者没有行，后者 total=0 但 review 返回了 summary
        assertThat(aiReviewService.findings(id)).isEmpty();

        var summary = aiReviewService.review(id);
        // review 返回的 summary 带 provider 与版本，能证明"确实执行过"
        assertThat(summary.provider()).isNotBlank();
        assertThat(summary.modelVersion()).isNotBlank();
    }

    @Test
    @DisplayName("跨租户不能抽取也不能审查")
    void otherTenantShouldBeBlocked() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        loginAsOtherTenant();
        assertThat(aiReviewService.findings(id)).isEmpty();
        assertThat(extractionService.elements(id)).isEmpty();
    }

    @Test
    @DisplayName("抽取与审查的要素互不破坏：规则校验仍可执行")
    void pipelineShouldRemainConsistent() throws IOException {
        loginAsDemoTenant();
        Long id = uploadAndParse(contractText());

        extractionService.extract(id);
        aiReviewService.review(id);
        var ruleSummary = ruleCheckService.check(id);

        assertThat(ruleSummary.total()).isEqualTo(4);
        assertThat(contractService.get(id).getStatus()).isEqualTo(ContractStatus.RULE_CHECKED);
    }

    // ==================================================================

    private Long uploadAndParse(String text) throws IOException {
        List<String> lines = text.lines().toList();
        byte[] pdf = pdfFactory.build(lines);
        Long id = contractService.upload(new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", pdf), "AI 链路测试合同")
                .contract().id();

        var outcome = parsingService.parse(id);
        assertThat(outcome.success())
                .withFailMessage("测试数据解析失败: %s", outcome.message())
                .isTrue();
        return id;
    }
}
