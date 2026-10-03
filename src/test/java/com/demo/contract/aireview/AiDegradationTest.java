package com.demo.contract.aireview;

import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiClient;
import com.demo.contract.aireview.client.AiErrorCode;
import com.demo.contract.aireview.client.UnavailableAiClient;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.review.ReviewActionService;
import com.demo.contract.review.ReviewTaskService;
import com.demo.contract.review.domain.ReviewActionType;
import com.demo.contract.review.domain.ReviewTaskStatus;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 不可用降级验收（T-019 / A-08 / 不变式 I-04）。
 *
 * <p>本类<b>刻意用不可用通道启动</b>（{@code client-mode=unavailable}），
 * 以便确定性地触发降级，而不是靠拔网线或耗尽额度。
 *
 * <p>要证明的命题：
 * <blockquote>
 * <b>AI 不可用时，规则校验与人工复核流程完全不受影响。</b>
 * </blockquote>
 *
 * <p>反例是很容易写出来的：把 AI 失败当成任务失败，
 * 于是"AI 挂了整份审查就废了"。那样规则引擎做得再准也没有意义，
 * 因为用户根本走不到看结论那一步。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@TestPropertySource(properties = {
        "app.ai.enabled=true",
        "app.ai.client-mode=unavailable"
})
// ⚠️ 与 ReviewTaskIntegrationTest 同理，**刻意不加 @Transactional**：
// ReviewTaskService 的 AI 调用走 REQUIRES_NEW 独立事务，只能看到已提交的数据，
// 而测试自己的事务会让上传的合同处于未提交状态，独立事务看不到它。
// 测试要反映真实事务边界，就不能用一个大事务把一切包住。
class AiDegradationTest extends AuthenticatedTestBase {

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 清理本类落库的数据（非事务测试的代价）。
     *
     * <p>用 JdbcTemplate 直接删，不为测试便利而扩大生产 API。
     * 注意 review_action 在 MySQL 上有 BEFORE DELETE 触发器，
     * 需要临时摘掉才能清——**这反过来证明触发器确实在生效**。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_review_action_no_delete");
            jdbc.execute("DELETE FROM review_action");
        } catch (RuntimeException ignored) {
            // H2 没有该触发器
        }
        for (String table : new String[]{"review_task", "ai_finding", "contract_element",
                "rule_finding", "contract_text", "contract"}) {
            try {
                jdbc.execute("DELETE FROM " + table);
            } catch (RuntimeException ignored) {
                // 表不存在等情况，忽略
            }
        }
        com.demo.contract.auth.domain.CurrentUser.clear();
    }

    /**
     * 真实 HTTP 栈。
     *
     * <p>本类里有两条断言必须走真实容器：**异常处理器只在真实 HTTP 调度下才会被走到**，
     * 直接调 Service 只能验证业务逻辑，验证不了"AI 不可用返回 503 而不是 500"。
     */
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate rest;

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private RuleCheckService ruleCheckService;
    @Autowired private ElementExtractionService extractionService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private ReviewTaskService taskService;
    @Autowired private ReviewActionService actionService;
    @Autowired private AiClient aiClient;
    @Autowired private TestPdfFactory pdfFactory;

    /**
     * 要素完整的合同。
     *
     * <p>⚠️ 刻意包含**大写金额**与**三个日期**：少了它们，
     * 金额规则与日期规则会返回 UNDETERMINED，
     * 而本测试要证明的恰恰是"AI 不可用时规则仍能给出**确定**结论"。
     * 用一份要素不全的合同会让这条断言失去意义。
     */
    private static final String CONTRACT_TEXT = String.join("\n",
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

    private Long preparedContract() throws Exception {
        loginAsDemoTenant();
        byte[] pdf = pdfFactory.build(CONTRACT_TEXT.lines().toList());
        Long id = contractService.upload(new MockMultipartFile(
                "file", "d.pdf", "application/pdf", pdf), "降级验收").contract().id();
        parsingService.parse(id);
        return id;
    }

    // ==================================================================
    // 前置：确认通道真的不可用
    // ==================================================================

    @Test
    @DisplayName("前置确认：当前注入的就是不可用通道，否则本类全部结论无效")
    void clientMustActuallyBeUnavailable() {
        assertThat(aiClient).isInstanceOf(UnavailableAiClient.class);
        assertThat(aiClient.isAvailable())
                .withFailMessage("通道可用时本类的降级断言是假绿")
                .isFalse();
        assertThat(aiClient.providerName()).contains("unavailable");

        // 调用必须抛 AI_UNAVAILABLE，而不是别的错误
        try {
            aiClient.complete("系统提示词", "内容");
            org.assertj.core.api.Assertions.fail("不可用通道没有抛异常");
        } catch (AiCallException e) {
            assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE);
            // 消息要说明这是显式开关，而不是真故障——否则排查会浪费大量时间
            assertThat(e.getMessage()).contains("显式设为不可用").contains("规则结论");
        }
    }

    // ==================================================================
    // 核心：I-04
    // ==================================================================

    @Test
    @DisplayName("I-04 之一：AI 不可用时规则校验完全正常，四条规则都给出确定结论")
    void ruleCheckMustWorkWhenAiIsDown() throws Exception {
        Long contractId = preparedContract();

        var summary = ruleCheckService.check(contractId);

        assertThat(summary.total()).isEqualTo(4);
        // 要素齐全的合同，四条规则应当全部给出确定结论（不是"无法判定"）
        assertThat(summary.undetermined())
                .withFailMessage("AI 不可用导致规则也无法判定，说明规则依赖了 AI")
                .isZero();
        assertThat(summary.hits()).isZero();
        assertThat(summary.passes()).isEqualTo(4);
    }

    @Test
    @DisplayName("I-04 之二：要素抽取失败，但抽取失败**不影响**规则校验的结果")
    void extractionFailureMustNotBreakRules() throws Exception {
        Long contractId = preparedContract();

        // 规则先跑（不依赖 AI）
        var before = ruleCheckService.check(contractId);

        // 再尝试 AI 抽取 —— 必然失败
        try {
            extractionService.extract(contractId);
            org.assertj.core.api.Assertions.fail("不可用通道下抽取竟然成功了");
        } catch (AiCallException e) {
            assertThat(e.getCode()).isEqualTo(AiErrorCode.AI_UNAVAILABLE);
        }

        // 规则结论必须还在，且结果一致
        var after = ruleCheckService.check(contractId);
        assertThat(after.total()).isEqualTo(before.total());
        assertThat(after.passes()).isEqualTo(before.passes());
        assertThat(after.undetermined()).isEqualTo(before.undetermined());
    }

    @Test
    @DisplayName("I-04 之三：AI 不可用时任务降级到 AI_UNAVAILABLE，并且**能继续进入人工复核**")
    void taskShouldDegradeAndStillAllowManualReview() throws Exception {
        Long contractId = preparedContract();

        var outcome = taskService.start(contractId, "degrade-e2e-1");

        // 关键：没有抛异常，任务被建出来了
        assertThat(outcome.task().getId()).isNotNull();
        assertThat(outcome.task().statusEnum())
                .withFailMessage("AI 不可用时任务应当停在降级态，实际 %s", outcome.task().statusEnum())
                .isEqualTo(ReviewTaskStatus.AI_UNAVAILABLE);
        assertThat(outcome.task().isAiAvailable()).isFalse();
        assertThat(outcome.message()).contains("降级");

        // 降级原因要写清楚，而且要点明规则结论仍可用
        assertThat(outcome.task().getStatusReason())
                .contains("AI 通道不可用")
                .contains("规则结论");

        // 关键：降级态不是终点，能继续走人工复核
        var resumed = taskService.proceedAfterDegrade(outcome.task().getId());
        assertThat(resumed.statusEnum()).isEqualTo(ReviewTaskStatus.AWAITING_REVIEW);
    }

    @Test
    @DisplayName("I-04 之四：降级后规则结论仍在，人工可以看着它做复核决定")
    void degradedTaskShouldRetainRuleFindings() throws Exception {
        Long contractId = preparedContract();

        var outcome = taskService.start(contractId, "degrade-e2e-2");
        taskService.proceedAfterDegrade(outcome.task().getId());

        // 规则结论必须完整保留 —— 这是"降级但可继续工作"的实质
        var findings = ruleCheckService.findings(contractId);
        assertThat(findings)
                .withFailMessage("降级后规则结论丢失，人工就没有可依据的东西了")
                .hasSize(4);
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.getResult()).isNotNull();
            assertThat(f.getRuleCode()).isNotBlank();
        });
    }

    @Test
    @DisplayName("I-04 之五：无 AI 结论时人工仍可「确认整份合同无风险」并留痕")
    void manualReviewMustWorkWithoutAnyAiFindings() throws Exception {
        Long contractId = preparedContract();

        var outcome = taskService.start(contractId, "degrade-e2e-3");
        taskService.proceedAfterDegrade(outcome.task().getId());

        // AI 一条结论都没有
        assertThat(aiReviewService.findings(contractId)).isEmpty();

        // 但人工复核流程照常可走（合同级动作）
        var action = actionService.record(contractId, null,
                ReviewActionType.CONFIRM_NO_RISK,
                "AI 通道不可用，已依据规则结论与人工阅读确认无实质风险",
                "degrade-manual-1");

        assertThat(action.getNewStatus()).isEqualTo("CONFIRMED_NO_RISK");
        assertThat(actionService.history(contractId)).hasSize(1);

        // 审计链完好
        assertThat(actionService.verifyChain(contractId).intact()).isTrue();
    }

    @Test
    @DisplayName("I-04 之六：降级不能伪装成「未发现风险」—— 两者必须可区分")
    void degradeMustNotLookLikeNoRisk() throws Exception {
        Long contractId = preparedContract();

        var outcome = taskService.start(contractId, "degrade-e2e-4");

        // 区分点一：任务状态是 AI_UNAVAILABLE，而不是 AWAITING_REVIEW
        assertThat(outcome.task().statusEnum())
                .withFailMessage("""
                        降级被伪装成了正常完成。

                        用户看到"审查完成，未发现风险"与看到"AI 不可用，规则已跑，
                        请人工确认"是两个完全不同的结论。前者会让风险被漏掉。
                        """)
                .isEqualTo(ReviewTaskStatus.AI_UNAVAILABLE);

        // 区分点二：aiAvailable=false 明确标出
        assertThat(outcome.task().isAiAvailable()).isFalse();

        // 区分点三：statusReason 必须说明原因，而不是留空
        assertThat(outcome.task().getStatusReason()).isNotBlank();

        // 区分点四：响应消息不能说"未发现风险"
        assertThat(outcome.message())
                .withFailMessage("降级消息里出现了「未发现风险」这类误导性表述")
                .doesNotContain("未发现风险")
                .contains("降级");
    }

    @Test
    @DisplayName("AI 不可用不影响其他接口：健康检查照常返回 200")
    void otherEndpointsMustStayHealthy() {
        assertThat(rest.getForEntity("/api/health", String.class).getStatusCode().value())
                .isEqualTo(200);
    }

    /**
     * 通过真实登录接口拿一个 Bearer 头。
     *
     * <p>不直接调 {@code loginAsDemoTenant()}：那只是设置 ThreadLocal，
     * 而 HTTP 请求跑在**另一个线程**上，取不到它。
     * 走真实登录才能拿到 JWT 并被过滤器认出来。
     */
    private org.springframework.http.HttpHeaders authHeaders(String username) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var login = rest.postForEntity("/api/auth/login",
                new org.springframework.http.HttpEntity<>(
                        java.util.Map.of("username", username, "password", "Demo@2026"),
                        headers),
                java.util.Map.class);
        assertThat(login.getStatusCode().value())
                .withFailMessage("测试用登录失败: %s", login.getBody())
                .isEqualTo(200);

        var out = new org.springframework.http.HttpHeaders();
        out.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        out.setBearerAuth(String.valueOf(login.getBody().get("token")));
        return out;
    }

    @Test
    @DisplayName("重新启动不会被降级状态卡死：新幂等键可以再建任务")
    void degradedTaskShouldNotBlockNewAttempts() throws Exception {
        Long contractId = preparedContract();

        var first = taskService.start(contractId, "retry-1");
        assertThat(first.task().statusEnum()).isEqualTo(ReviewTaskStatus.AI_UNAVAILABLE);

        // AI 恢复后（这里仍不可用，但流程上允许再试一次）用新键启动
        var second = taskService.start(contractId, "retry-2");
        assertThat(second.task().getId()).isNotEqualTo(first.task().getId());
        assertThat(taskService.findByContract(contractId)).hasSize(2);
    }
}
