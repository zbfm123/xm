package com.demo.contract.review;

import com.demo.contract.aireview.AiReviewService;
import com.demo.contract.aireview.domain.AiFindingRow;
import com.demo.contract.aireview.mapper.AiFindingMapper;
import com.demo.contract.auth.domain.Role;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.review.domain.ReviewActionType;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人工复核的权限与状态闸门。
 *
 * <h2>为什么补这个测试类</h2>
 *
 * {@code docs/modules/workflow.md} 与 README 早就写明了两条规则：
 * <ul>
 *   <li>低置信度条目<b>仅 {@code LEGAL_LEAD} 可终审</b>，{@code DEMO_READONLY} 一律拒绝
 *       （403 {@code INSUFFICIENT_ROLE}）</li>
 *   <li>重复复核终态条目 → 409 {@code ALREADY_REVIEWED}</li>
 * </ul>
 *
 * <p>但 2026-10-07 审计发现：<b>代码里一条都没有实现</b>——
 * {@code ReviewActionService.record()} 完全不做角色判定，
 * 全仓 grep 不到 {@code INSUFFICIENT_ROLE} / {@code ALREADY_REVIEWED}，
 * 也没有 {@code @PreAuthorize}。
 * 后果是 {@code staff01} 与 {@code lead01} 在代码里权限完全一样，
 * 演示时被要求"用专员账号终审一下试试"会当场露馅。
 *
 * <h2>这个测试真正要证明的是什么</h2>
 *
 * 权限这种东西，测"有权限的人能做"是没有意义的——
 * 真正要证明的是<b>"没权限的人做不了"</b>。
 * 所以下面每条规则都有一个正向用例和一个反向用例，
 * 缺了反向的那个，判定逻辑写反了也测不出来。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class ReviewPermissionIntegrationTest extends AuthenticatedTestBase {

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private AiFindingMapper findingMapper;
    @Autowired private ReviewActionService reviewService;
    @Autowired private TestPdfFactory pdfFactory;
    @Autowired private JdbcTemplate jdbc;

    private static final String CONTRACT_TEXT = String.join("\n",
            "服务合同（权限测试样例）",
            "甲方：北京某某科技有限公司",
            "乙方：上海某某贸易有限公司",
            "付款方式：另行约定。",
            "因本合同产生的一切损失均由乙方承担全部责任。");

    /** 上传并解析一份合同，返回 contractId（不跑 AI，findings 由测试自己造）。 */
    private Long preparedContract() throws Exception {
        loginAsDemoTenant();
        byte[] pdf = pdfFactory.build(CONTRACT_TEXT.lines().toList());
        Long id = contractService.upload(new MockMultipartFile(
                "file", "perm.pdf", "application/pdf", pdf), "权限测试合同").contract().id();
        parsingService.parse(id);
        return id;
    }

    /**
     * 直插一条指定状态的 AI 结论。
     *
     * <p>为什么直插而不用 {@code aiReviewService.review()}：
     * 真实审查的置信度由 Mock 桩与对齐结果决定，
     * <b>无法稳定地造出"低置信度"</b>这个前置条件。
     * 这里要测的是权限判定，前置条件必须可控。
     *
     * <p>用 JdbcTemplate 直插是安全的：{@code ai_finding} 是<b>机器结论</b>表，
     * 允许随复核变化（与只追加的 {@code review_action} 不同）。
     */
    private Long seedFinding(Long contractId, String status, double confidence) {
        jdbc.update("INSERT INTO ai_finding (tenant_id, contract_id, risk_type, quote, "
                        + "char_start, char_end, confidence, match_level, status, status_reason, "
                        + "model_version, prompt_version) "
                        + "VALUES (?, ?, 'VAGUE_PAYMENT', ?, 0, 10, ?, 'EXACT', ?, ?, 'mock-v1', 'v1')",
                DEMO_TENANT, contractId, "付款方式：另行约定。", confidence, status,
                "测试造数据，状态=" + status);
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM ai_finding WHERE contract_id = ?", Long.class, contractId);
        assertThat(id).as("造 finding 失败").isNotNull();
        return id;
    }

    /** 确认某条 finding 的当前状态。 */
    private String statusOf(Long contractId, Long findingId) {
        return findingMapper.findByContract(contractId, DEMO_TENANT).stream()
                .filter(f -> findingId.equals(f.getId()))
                .map(AiFindingRow::getStatus)
                .findFirst()
                .orElse(null);
    }

    // ==================================================================
    // 规则一：低置信度条目的终审只有法务主管能做
    // ==================================================================

    @Test
    @DisplayName("⚠️ 反向：专员（LEGAL_STAFF）不能采纳低置信度条目 —— 403 INSUFFICIENT_ROLE")
    void staffCannotAcceptLowConfidenceFinding() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "LOW_CONFIDENCE", 0.35);

        loginAsDemoTenant(Role.LEGAL_STAFF);

        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "我觉得可以采纳", "perm-key-1"))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).getCode())
                .isEqualTo(ReviewErrorCode.INSUFFICIENT_ROLE);

        assertThat(statusOf(contractId, findingId))
                .as("被拒绝的动作不能改动结论状态")
                .isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("⚠️ 反向：专员也不能驳回低置信度条目（驳回同样是终审）")
    void staffCannotRejectLowConfidenceFinding() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "LOW_CONFIDENCE", 0.35);

        loginAsDemoTenant(Role.LEGAL_STAFF);

        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.REJECT, "不构成风险", "perm-key-2"))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).getCode())
                .isEqualTo(ReviewErrorCode.INSUFFICIENT_ROLE);
    }

    @Test
    @DisplayName("正向：主管（LEGAL_LEAD）可以终审低置信度条目")
    void leadCanFinalizeLowConfidenceFinding() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "LOW_CONFIDENCE", 0.35);

        loginAsDemoTenant(Role.LEGAL_LEAD);

        var recorded = reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "核对原文后确认风险成立", "perm-key-3");

        assertThat(recorded).isNotNull();
        assertThat(recorded.getOperatorName()).isEqualTo("lead01");
        assertThat(statusOf(contractId, findingId)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("专员对低置信度条目仍可 ESCALATE（升级给主管不是越权下结论）")
    void staffMayEscalateLowConfidenceFinding() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "LOW_CONFIDENCE", 0.35);

        loginAsDemoTenant(Role.LEGAL_STAFF);

        reviewService.record(contractId, findingId,
                ReviewActionType.ESCALATE, "这条我拿不准，请主管看", "perm-key-4");

        assertThat(statusOf(contractId, findingId))
                .as("升级是把问题往上交，不是越权下结论，因此不挡")
                .isEqualTo("ESCALATED");
    }

    @Test
    @DisplayName("专员可以正常终审普通（PENDING）条目 —— 权限判定不能误伤正常流程")
    void staffCanFinalizeNormalFinding() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "PENDING", 0.92);

        loginAsDemoTenant(Role.LEGAL_STAFF);

        reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "证据充分", "perm-key-5");

        assertThat(statusOf(contractId, findingId)).isEqualTo("ACCEPTED");
    }

    // ==================================================================
    // 规则二：只读演示账号一律拒绝
    // ==================================================================

    @Test
    @DisplayName("⚠️ 只读账号（DEMO_READONLY）连普通条目都不能复核")
    void readOnlyAccountCannotReviewAnything() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "PENDING", 0.92);

        loginAsDemoTenant(Role.DEMO_READONLY);

        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "只读账号不该能写", "perm-key-6"))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).getCode())
                .isEqualTo(ReviewErrorCode.INSUFFICIENT_ROLE);

        assertThat(statusOf(contractId, findingId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("⚠️ 只读判定必须优先于低置信度判定（否则会漏过非低置信度的条目）")
    void readOnlyCheckMustComeBeforeConfidenceCheck() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "LOW_CONFIDENCE", 0.35);

        loginAsDemoTenant(Role.DEMO_READONLY);

        // 就算角色是"主管"也拦不住只读——但这里角色就是只读，
        // 关键是这个条目是 LOW_CONFIDENCE：如果实现里先判"是不是主管"、
        // 再判"是不是只读"，那非低置信度条目就会从只读账号手下溜过去。
        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "只读账号", "perm-key-7"))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).getCode())
                .isEqualTo(ReviewErrorCode.INSUFFICIENT_ROLE);
    }

    // ==================================================================
    // 重复复核终态条目
    // ==================================================================

    @Test
    @DisplayName("⚠️ 已 ACCEPTED 的条目不能再次裁决 —— 409 ALREADY_REVIEWED")
    void alreadyReviewedFindingCannotBeReviewedAgain() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "PENDING", 0.92);

        loginAsDemoTenant(Role.LEGAL_STAFF);
        reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "第一次，证据充分", "perm-key-8");
        assertThat(statusOf(contractId, findingId)).isEqualTo("ACCEPTED");

        // 换个人、换个幂等键再裁决一次 —— 必须被状态闸门挡住，
        // 而不是靠幂等键（幂等键只挡"同一个请求重放"，挡不住"换个人改结论"）
        loginAsDemoTenant(Role.LEGAL_LEAD);
        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.REJECT, "我不同意，改成驳回", "perm-key-9"))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).getCode())
                .isEqualTo(ReviewErrorCode.ALREADY_REVIEWED);

        assertThat(statusOf(contractId, findingId))
                .as("被拒绝的第二次裁决不能改动已定的结论")
                .isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("已 REJECTED 的条目同样不能再裁决")
    void rejectedFindingCannotBeReviewedAgain() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "PENDING", 0.92);

        loginAsDemoTenant(Role.LEGAL_STAFF);
        reviewService.record(contractId, findingId,
                ReviewActionType.REJECT, "不构成风险", "perm-key-10");

        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "反悔了", "perm-key-11"))
                .isInstanceOf(ReviewException.class)
                .extracting(e -> ((ReviewException) e).getCode())
                .isEqualTo(ReviewErrorCode.ALREADY_REVIEWED);
    }

    @Test
    @DisplayName("非终态（ESCALATED）条目仍可继续被终审 —— 闸门不能把流程堵死")
    void escalatedFindingCanStillBeFinalized() throws Exception {
        Long contractId = preparedContract();
        Long findingId = seedFinding(contractId, "PENDING", 0.92);

        loginAsDemoTenant(Role.LEGAL_STAFF);
        reviewService.record(contractId, findingId,
                ReviewActionType.ESCALATE, "请主管看", "perm-key-12");
        assertThat(statusOf(contractId, findingId)).isEqualTo("ESCALATED");

        // ESCALATED 不是终态，主管可以接着裁决
        loginAsDemoTenant(Role.LEGAL_LEAD);
        reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "主管已确认", "perm-key-13");

        assertThat(statusOf(contractId, findingId)).isEqualTo("ACCEPTED");
    }
}
