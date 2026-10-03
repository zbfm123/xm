package com.demo.contract.review;

import com.demo.contract.aireview.AiReviewService;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.review.audit.ReviewActionHasher;
import com.demo.contract.review.domain.ReviewActionRow;
import com.demo.contract.review.domain.ReviewActionType;
import com.demo.contract.review.mapper.ReviewActionMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人工复核与只追加审计集成测试（T-018）。
 *
 * <p>核心命题：<b>复核痕迹只追加、可自证未被改动。</b>
 *
 * <p>篡改检测部分刻意用 {@link JdbcTemplate} 绕过 Mapper 直接改库——
 * 因为要验证的正是"即使绕过了应用层的约束，篡改依然能被发现"。
 * 用 Mapper 改是改不动的（它没有修改方法），那样测不到哈希链本身。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class ReviewActionIntegrationTest extends AuthenticatedTestBase {

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private ReviewActionService reviewService;
    @Autowired private ReviewActionMapper actionMapper;
    @Autowired private TestPdfFactory pdfFactory;
    @Autowired private JdbcTemplate jdbc;

    private static final String CONTRACT_TEXT = String.join("\n",
            "服务合同（虚构样例）",
            "甲方：北京某某科技有限公司",
            "乙方：上海某某贸易有限公司",
            "付款方式：另行约定。",
            "因本合同产生的一切损失均由乙方承担全部责任。",
            "本合同期满后自动续约一年。");

    private Long preparedContractWithFindings() throws Exception {
        loginAsDemoTenant();
        byte[] pdf = pdfFactory.build(CONTRACT_TEXT.lines().toList());
        Long id = contractService.upload(new MockMultipartFile(
                "file", "r.pdf", "application/pdf", pdf), "复核测试").contract().id();
        parsingService.parse(id);
        aiReviewService.review(id);
        return id;
    }

    // ==================================================================
    // 记录与哈希链
    // ==================================================================

    @Test
    @DisplayName("记录复核动作后哈希链完好，且链首的 previousHash 是全 0")
    void recordedActionsShouldFormAnIntactChain() throws Exception {
        Long contractId = preparedContractWithFindings();
        var findings = aiReviewService.findings(contractId);
        assertThat(findings).isNotEmpty();

        reviewService.record(contractId, findings.get(0).getId(),
                ReviewActionType.ACCEPT, "证据充分，确认该风险成立", "key-1");
        if (findings.size() > 1) {
            reviewService.record(contractId, findings.get(1).getId(),
                    ReviewActionType.REJECT, "该条款属正常商业安排", "key-2");
        }

        List<ReviewActionRow> history = reviewService.history(contractId);
        assertThat(history).hasSizeGreaterThanOrEqualTo(1);

        // 链首前驱是全 0，而不是 null
        assertThat(history.get(0).getPreviousHash())
                .isEqualTo(ReviewActionHasher.GENESIS_HASH);
        assertThat(history.get(0).getPreviousHash()).hasSize(64);

        // 每条记录都带操作人——审计没有操作人就没有意义
        assertThat(history).allSatisfy(r -> {
            assertThat(r.getOperatorId()).isNotNull();
            assertThat(r.getOperatorName()).isNotBlank();
            assertThat(r.getRecordHash()).hasSize(64);
        });

        var verification = reviewService.verifyChain(contractId);
        assertThat(verification.intact()).isTrue();
        assertThat(verification.checked()).isEqualTo(history.size());
    }

    @Test
    @DisplayName("链式相连：第 N 条的 previousHash 等于第 N-1 条的 recordHash")
    void chainShouldLinkRecords() throws Exception {
        Long contractId = preparedContractWithFindings();
        var findings = aiReviewService.findings(contractId);
        assertThat(findings).hasSizeGreaterThanOrEqualTo(2);

        reviewService.record(contractId, findings.get(0).getId(),
                ReviewActionType.ACCEPT, "理由一", "chain-1");
        reviewService.record(contractId, findings.get(1).getId(),
                ReviewActionType.REJECT, "理由二", "chain-2");

        List<ReviewActionRow> history = reviewService.history(contractId);
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).getPreviousHash())
                    .withFailMessage("第 %d 条没有指向前一条的哈希", i + 1)
                    .isEqualTo(history.get(i - 1).getRecordHash());
        }
    }

    @Test
    @DisplayName("同一幂等键重放不产生第二条记录 —— 审计写错后无法删除，必须在入口挡住")
    void sameIdempotencyKeyShouldNotCreateSecondRecord() throws Exception {
        Long contractId = preparedContractWithFindings();
        Long findingId = aiReviewService.findings(contractId).get(0).getId();

        ReviewActionRow first = reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "第一次提交", "same-key");
        ReviewActionRow second = reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "第一次提交", "same-key");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getRecordHash()).isEqualTo(first.getRecordHash());
        assertThat(reviewService.history(contractId)).hasSize(1);
    }

    @Test
    @DisplayName("缺少幂等键时直接拒绝，而不是默默写入一条无法去重的记录")
    void missingIdempotencyKeyShouldBeRejected() throws Exception {
        Long contractId = preparedContractWithFindings();
        Long findingId = aiReviewService.findings(contractId).get(0).getId();

        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "理由", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幂等键");
        assertThat(reviewService.history(contractId)).isEmpty();
    }

    // ==================================================================
    // 篡改检测
    // ==================================================================

    @Test
    @DisplayName("改动一条记录的理由 → 哈希链断开，并指出是哪一条")
    void tamperedContentShouldBreakTheChain() throws Exception {
        Long contractId = preparedContractWithFindings();
        var findings = aiReviewService.findings(contractId);
        assertThat(findings).hasSizeGreaterThanOrEqualTo(2);

        reviewService.record(contractId, findings.get(0).getId(),
                ReviewActionType.ACCEPT, "原始理由：证据充分", "tamper-1");
        reviewService.record(contractId, findings.get(1).getId(),
                ReviewActionType.REJECT, "原始理由：正常条款", "tamper-2");

        assertThat(reviewService.verifyChain(contractId).intact()).isTrue();

        // 绕过 Mapper 直接改库，模拟有人直接改数据
        Long targetId = reviewService.history(contractId).get(0).getId();
        int updated = jdbc.update(
                "UPDATE review_action SET reason = ? WHERE id = ?",
                "被人改过的理由", targetId);
        assertThat(updated).isEqualTo(1);

        var verification = reviewService.verifyChain(contractId);
        assertThat(verification.intact())
                .withFailMessage("审计记录被改动但哈希链校验仍通过，篡改检测失效")
                .isFalse();
        assertThat(verification.brokenAt()).isEqualTo(targetId);
        assertThat(verification.reason()).contains("已被改动");
    }

    @Test
    @DisplayName("删除中间一条记录 → 哈希链断开（顺序被改动同样可检测）")
    void deletedRecordShouldBreakTheChain() throws Exception {
        Long contractId = preparedContractWithFindings();
        var findings = aiReviewService.findings(contractId);
        assertThat(findings).hasSizeGreaterThanOrEqualTo(3);

        for (int i = 0; i < 3; i++) {
            reviewService.record(contractId, findings.get(i).getId(),
                    ReviewActionType.ACCEPT, "理由" + i, "del-" + i);
        }
        List<ReviewActionRow> before = reviewService.history(contractId);
        Long middleId = before.get(1).getId();
        Long lastId = before.get(2).getId();

        jdbc.update("DELETE FROM review_action WHERE id = ?", middleId);

        var verification = reviewService.verifyChain(contractId);
        assertThat(verification.intact())
                .withFailMessage("中间记录被删除但校验仍通过——"
                        + "说明校验只比对了内容，没有比对链的前后关系")
                .isFalse();
        // 断点应当报在"接不上"的那一条（原第 3 条）
        assertThat(verification.brokenAt()).isEqualTo(lastId);
        assertThat(verification.reason()).contains("前驱哈希不匹配");
    }

    @Test
    @DisplayName("伪造一条重新计算过自身哈希的记录，仍会被链关系检测出来")
    void forgedRecordWithRecomputedHashShouldStillBeDetected() throws Exception {
        Long contractId = preparedContractWithFindings();
        var findings = aiReviewService.findings(contractId);
        assertThat(findings).hasSizeGreaterThanOrEqualTo(2);

        reviewService.record(contractId, findings.get(0).getId(),
                ReviewActionType.ACCEPT, "原始理由", "forge-1");
        reviewService.record(contractId, findings.get(1).getId(),
                ReviewActionType.REJECT, "原始理由二", "forge-2");

        Long targetId = reviewService.history(contractId).get(0).getId();
        ReviewActionRow target = actionMapper.findByIdempotencyKey(
                TenantContext.requireTenantId(), "forge-1");
        assertThat(target.getId()).isEqualTo(targetId);

        // 攻击者改了内容，并把这一条自己的哈希也重算成"正确"的样子
        String tamperedReason = "篡改后的理由";
        ReviewActionRow forged = new ReviewActionRow(
                target.getTenantId(), target.getContractId(), target.getFindingId(),
                target.getIdempotencyKey(), target.getAction(), tamperedReason,
                target.getOperatorId(), target.getOperatorName(),
                target.getPreviousStatus(), target.getNewStatus(),
                null, target.getPreviousHash());
        String forgedHash = ReviewActionHasher.compute(forged);

        jdbc.update("UPDATE review_action SET reason = ?, record_hash = ? WHERE id = ?",
                tamperedReason, forgedHash, targetId);

        // 单看第一条，它现在是"自洽"的；但第二条的 previous_hash 还指向旧哈希
        var verification = reviewService.verifyChain(contractId);
        assertThat(verification.intact())
                .withFailMessage("重算了单条哈希的伪造没有被检测到——"
                        + "这正是链式结构存在的意义：改一条会波及其后所有记录")
                .isFalse();
    }

    @Test
    @DisplayName("没有复核记录时链是完好的（checked=0），而不是'校验失败'")
    void emptyChainShouldBeIntact() throws Exception {
        Long contractId = preparedContractWithFindings();
        var verification = reviewService.verifyChain(contractId);
        assertThat(verification.intact()).isTrue();
        assertThat(verification.checked()).isZero();
    }

    // ==================================================================
    // 状态与隔离
    // ==================================================================

    @Test
    @DisplayName("复核动作把结论状态从候选态推进到已处理态，且记录前后状态")
    void reviewShouldAdvanceFindingStatus() throws Exception {
        Long contractId = preparedContractWithFindings();
        Long findingId = aiReviewService.findings(contractId).get(0).getId();

        ReviewActionRow row = reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "确认成立", "status-1");

        assertThat(row.getNewStatus()).isEqualTo("ACCEPTED");
        // 前后状态都要记下来，否则无法从审计日志重建状态变迁
        assertThat(row.getPreviousStatus()).isNotBlank();

        var updated = aiReviewService.findings(contractId).stream()
                .filter(f -> f.getId().equals(findingId)).findFirst().orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ACCEPTED");
        assertThat(updated.getStatusReason()).contains("确认成立");
    }

    @Test
    @DisplayName("CONFIRM_NO_RISK 是合同级动作，不需要 findingId")
    void confirmNoRiskShouldBeContractLevel() throws Exception {
        Long contractId = preparedContractWithFindings();

        ReviewActionRow row = reviewService.record(contractId, null,
                ReviewActionType.CONFIRM_NO_RISK, "已逐条核对，无实质风险", "no-risk-1");

        assertThat(row.getFindingId()).isNull();
        assertThat(row.getNewStatus()).isEqualTo("CONFIRMED_NO_RISK");
    }

    @Test
    @DisplayName("指向具体结论的动作必须给 findingId —— 否则报告会出现无法追溯的'已采纳'")
    void acceptWithoutFindingIdShouldBeRejected() throws Exception {
        Long contractId = preparedContractWithFindings();

        assertThatThrownBy(() -> reviewService.record(contractId, null,
                ReviewActionType.ACCEPT, "理由", "bad-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须指向一条具体的 AI 结论");
    }

    @Test
    @DisplayName("跨租户不可读复核历史，也不可记录")
    void otherTenantShouldBeBlocked() throws Exception {
        Long contractId = preparedContractWithFindings();
        Long findingId = aiReviewService.findings(contractId).get(0).getId();
        reviewService.record(contractId, findingId, ReviewActionType.ACCEPT, "理由", "iso-1");

        loginAsOtherTenant();
        assertThat(reviewService.history(contractId)).isEmpty();
        assertThatThrownBy(() -> reviewService.record(contractId, findingId,
                ReviewActionType.ACCEPT, "越权理由", "iso-2"))
                .isInstanceOf(com.demo.contract.parse.ContractException.class);
    }

    @Test
    @DisplayName("复核记录不随合同删除而消失 —— 审计痕迹必须独立保留")
    void reviewHistoryShouldSurviveContractDeletion() throws Exception {
        Long contractId = preparedContractWithFindings();
        Long findingId = aiReviewService.findings(contractId).get(0).getId();
        reviewService.record(contractId, findingId, ReviewActionType.ACCEPT,
                "删除前记录", "survive-1");
        int before = reviewService.history(contractId).size();
        assertThat(before).isEqualTo(1);

        contractService.delete(contractId);

        // ⚠️ 这是一条**刻意的设计决定**，不是遗漏：
        // 审计记录不参与级联清理。合同被删除，但"谁在什么时候做了什么判断"
        // 这个事实不因此消失。
        //
        // 注意：TenantContext 仍在，直接查 Mapper 而不走 Service
        // （Service 会因合同不存在而抛异常）
        List<ReviewActionRow> after = actionMapper.findByContract(
                contractId, TenantContext.requireTenantId());
        assertThat(after)
                .withFailMessage("""
                        复核记录随合同一起被删除了。

                        审计痕迹必须独立保留（不变式 I-06）：合同被删除，
                        但'谁在什么时候做了什么判断'这个事实不因此消失。
                        如果你确实想让审计随合同清理，请先确认这不违反只追加要求。
                        """)
                .hasSize(before);
    }
}
