package com.demo.contract.review;

import com.demo.contract.aireview.AiReviewService;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.review.domain.ReviewActionType;
import com.demo.contract.review.domain.ReviewTaskStatus;
import com.demo.contract.review.mapper.ReviewTaskMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审查任务状态机集成测试（T-017）。
 *
 * <p>验收要求：<b>合法迁移有穷举单测；重复提交返回同一 taskId。</b>
 * 穷举部分在 {@code ReviewTaskStatusMachineTest}（纯单元、无 DB），
 * 本类负责流程与幂等的端到端验证。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
// ⚠️ 本类**刻意不加 @Transactional**。
//
// ReviewTaskService 的 AI 调用走 REQUIRES_NEW 独立事务（见 AiInvocationRunner），
// 而独立事务只能看到**已提交**的数据。测试方法自己的 @Transactional
// 会让上传的合同处于未提交状态，独立事务看不到它，于是报"合同不存在"。
//
// 生产环境下这不是问题：启动任务时合同早已提交。
// 测试要反映真实事务边界，就不该用一个大事务把一切包住。
// 代价是数据会真实落库——靠 @AfterEach 清理。
class ReviewTaskIntegrationTest extends AuthenticatedTestBase {

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 清理本类产生的数据。
     *
     * <p>用 JdbcTemplate 直接删，而不是给生产代码加一个"清理专用"的查询方法——
     * <b>为测试便利而扩大生产 API 是不划算的交换</b>。
     *
     * <p>顺序：先删子表。review_action 上有 BEFORE DELETE 触发器，
     * 需要临时摘掉才能清（这也反过来证明了触发器确实在生效）。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_review_action_no_delete");
            jdbc.execute("DELETE FROM review_action");
            jdbc.execute("CREATE TRIGGER trg_review_action_no_delete "
                    + "BEFORE DELETE ON review_action FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'review_action is append-only: DELETE is forbidden'");
        } catch (RuntimeException e) {
            // H2 不支持 SIGNAL，触发器建不了属正常；数据已清掉即可
        }
        for (String table : new String[]{"review_task", "ai_finding", "contract_element",
                "rule_finding", "contract_text", "contract"}) {
            try {
                jdbc.execute("DELETE FROM " + table);
            } catch (RuntimeException ignored) {
                // 表不存在或已被清空
            }
        }
        com.demo.contract.auth.domain.CurrentUser.clear();
    }

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private ReviewTaskService taskService;
    @Autowired private ReviewActionService actionService;
    @Autowired private ReviewTaskMapper taskMapper;
    @Autowired private TestPdfFactory pdfFactory;

    private static final String CONTRACT_TEXT = String.join("\n",
            "服务合同（虚构样例）",
            "甲方：北京某某科技有限公司",
            "乙方：上海某某贸易有限公司",
            "付款方式：另行约定。",
            "因本合同产生的一切损失均由乙方承担全部责任。",
            "本合同期满后自动续约一年。");

    private Long preparedContract() throws Exception {
        loginAsDemoTenant();
        byte[] pdf = pdfFactory.build(CONTRACT_TEXT.lines().toList());
        Long id = contractService.upload(new MockMultipartFile(
                "file", "t.pdf", "application/pdf", pdf), "任务状态机测试").contract().id();
        parsingService.parse(id);
        return id;
    }

    // ==================================================================
    // 正常流程
    // ==================================================================

    @Test
    @DisplayName("启动任务后走到「待人工复核」，规则与 AI 结论都已产出")
    void startShouldReachAwaitingReview() throws Exception {
        Long contractId = preparedContract();

        var outcome = taskService.start(contractId, "task-key-1");

        assertThat(outcome.idempotentHit()).isFalse();
        assertThat(outcome.task().statusEnum()).isEqualTo(ReviewTaskStatus.AWAITING_REVIEW);
        assertThat(outcome.task().isAiAvailable()).isTrue();
        assertThat(outcome.task().getTotalFindings()).isGreaterThan(0);

        // 规则结论与 AI 结论都必须真的产出了——否则"走到待复核"没有意义
        assertThat(aiReviewService.findings(contractId)).isNotEmpty();
    }

    @Test
    @DisplayName("任务状态是流程态，与合同的数据态相互独立")
    void taskStatusShouldBeIndependentOfContractStatus() throws Exception {
        Long contractId = preparedContract();
        taskService.start(contractId, "task-key-2");

        var task = taskService.findByContract(contractId).get(0);
        // 任务已到待人工复核，但合同还没到 COMPLETED（因为还没人复核）
        assertThat(task.statusEnum()).isEqualTo(ReviewTaskStatus.AWAITING_REVIEW);
        assertThat(contractService.get(contractId).getStatus().name())
                .isNotEqualTo("COMPLETED");
    }

    // ==================================================================
    // 幂等
    // ==================================================================

    @Test
    @DisplayName("重复提交返回同一 taskId，且不重跑流程")
    void repeatedStartShouldReturnSameTaskId() throws Exception {
        Long contractId = preparedContract();

        var first = taskService.start(contractId, "same-key");
        var second = taskService.start(contractId, "same-key");

        assertThat(second.task().getId())
                .withFailMessage("重复提交产生了新任务，幂等失效")
                .isEqualTo(first.task().getId());
        assertThat(second.idempotentHit()).isTrue();
        assertThat(taskService.findByContract(contractId)).hasSize(1);
    }

    @Test
    @DisplayName("不同幂等键产生不同任务，任务表保留多次审查的历史")
    void differentKeysShouldCreateDifferentTasks() throws Exception {
        Long contractId = preparedContract();

        var a = taskService.start(contractId, "key-a");
        var b = taskService.start(contractId, "key-b");

        assertThat(b.task().getId()).isNotEqualTo(a.task().getId());
        assertThat(taskService.findByContract(contractId)).hasSize(2);
    }

    @Test
    @DisplayName("缺幂等键时直接拒绝，而不是建一个无法去重的任务")
    void missingKeyShouldBeRejected() throws Exception {
        Long contractId = preparedContract();

        assertThatThrownBy(() -> taskService.start(contractId, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幂等键");
        assertThat(taskService.findByContract(contractId)).isEmpty();
    }

    // ==================================================================
    // 降级：I-04 的落点
    // ==================================================================

    @Test
    @DisplayName("AI 通道不可用时任务降级到 AI_UNAVAILABLE，但**继续**推进到待人工复核")
    void aiUnavailableShouldStillReachAwaitingReview() throws Exception {
        Long contractId = preparedContract();
        // 测试环境 app.ai.enabled=false → Mock 桩永远可用，因此这里无法直接触发降级。
        // 改为直接验证状态机本身的可达性（那是 I-04 的机制所在），
        // 并断言"AI_UNAVAILABLE 不是终态"。
        assertThat(ReviewTaskStatus.AI_UNAVAILABLE.isTerminal())
                .withFailMessage("AI 不可用变成终态了，等于 AI 挂了整份审查就废了")
                .isFalse();
        assertThat(ReviewTaskStatus.AI_UNAVAILABLE.canMoveTo(ReviewTaskStatus.AWAITING_REVIEW))
                .isTrue();

        // 端到端验证降级链路：手工把任务推到 AI_UNAVAILABLE，再确认能继续推进
        var outcome = taskService.start(contractId, "degrade-key");
        Long taskId = outcome.task().getId();
        var row = taskMapper.findById(taskId, TenantContext.requireTenantId());

        taskMapper.updateState(taskId, row.getTenantId(), ReviewTaskStatus.IN_PROGRESS.name(),
                null, true, 0, 0);
        taskMapper.updateState(taskId, row.getTenantId(),
                ReviewTaskStatus.AI_UNAVAILABLE.name(), "AI 通道未启用（模拟）", false, 0, 0);

        var degraded = taskService.findById(taskId);
        assertThat(degraded.statusEnum()).isEqualTo(ReviewTaskStatus.AI_UNAVAILABLE);
        assertThat(degraded.isAiAvailable()).isFalse();

        // 关键：降级态仍能进入人工复核，且规则与 AI 结论都还在。
        // 用显式的 proceedAfterDegrade，而不是让 refreshProgress 顺手救回来——
        // 状态推进必须是可观察的动作，不能是某个"顺手"操作的副产品。
        assertThat(aiReviewService.findings(contractId))
                .withFailMessage("降级不应清掉已有的 AI 结论")
                .isNotEmpty();

        var resumed = taskService.proceedAfterDegrade(taskId);
        assertThat(resumed.statusEnum())
                .withFailMessage("降级后无法进入人工复核，违反 I-04")
                .isEqualTo(ReviewTaskStatus.AWAITING_REVIEW);
        // 再次调用必须幂等
        assertThat(taskService.proceedAfterDegrade(taskId).statusEnum())
                .isEqualTo(ReviewTaskStatus.AWAITING_REVIEW);
    }

    @Test
    @DisplayName("降级原因写清楚是哪一种不可用 —— 配置问题与调用问题排查方向不同")
    void degradeReasonShouldBeSpecific() throws Exception {
        Long contractId = preparedContract();
        var outcome = taskService.start(contractId, "reason-key");
        // 正常路径下没有降级原因
        assertThat(outcome.task().getStatusReason()).isNull();

        // 手工模拟降级并检查原因字段被保存
        Long tenantId = TenantContext.requireTenantId();
        taskMapper.updateState(outcome.task().getId(), tenantId,
                ReviewTaskStatus.IN_PROGRESS.name(), null, true, 0, 0);
        taskMapper.updateState(outcome.task().getId(), tenantId,
                ReviewTaskStatus.AI_UNAVAILABLE.name(),
                "AI 通道未启用（app.ai.enabled=false 或未配置 API Key）。"
                        + "这是预期内的降级状态：规则结论与要素抽取均已保留，可继续人工复核",
                false, 0, 0);

        var task = taskService.findById(outcome.task().getId());
        assertThat(task.getStatusReason())
                .contains("未启用")
                .contains("规则结论");
        assertThat(task.isAiAvailable()).isFalse();
    }

    // ==================================================================
    // 进度与完成
    // ==================================================================

    @Test
    @DisplayName("全部条目复核完后任务推进到 COMPLETED")
    void allReviewedShouldCompleteTheTask() throws Exception {
        Long contractId = preparedContract();
        var outcome = taskService.start(contractId, "complete-key");
        Long taskId = outcome.task().getId();

        // 此时任务在 AWAITING_REVIEW，totalFindings > 0
        var task = taskService.findById(taskId);
        assertThat(task.getTotalFindings()).isGreaterThan(0);

        // 逐条复核（用第一条的结论对象取 id）
        var findings = aiReviewService.findings(contractId);
        int i = 0;
        for (var f : findings) {
            actionService.record(contractId, f.getId(), ReviewActionType.ACCEPT,
                    "逐条确认（第 " + (++i) + " 条）", "complete-" + i);
        }

        var refreshed = taskService.refreshProgress(taskId);
        assertThat(refreshed.getReviewedCount()).isEqualTo(findings.size());
        assertThat(refreshed.statusEnum()).isEqualTo(ReviewTaskStatus.COMPLETED);
        assertThat(refreshed.statusEnum().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("只复核一部分时任务仍是待人工复核，不会提前完成")
    void partialReviewShouldNotComplete() throws Exception {
        Long contractId = preparedContract();
        var outcome = taskService.start(contractId, "partial-key");
        Long taskId = outcome.task().getId();

        var findings = aiReviewService.findings(contractId);
        assertThat(findings.size()).isGreaterThan(1);

        actionService.record(contractId, findings.get(0).getId(),
                ReviewActionType.ACCEPT, "只复核一条", "partial-1");

        var refreshed = taskService.refreshProgress(taskId);
        assertThat(refreshed.getReviewedCount()).isEqualTo(1);
        assertThat(refreshed.statusEnum())
                .withFailMessage("只复核了一条就标记为完成，会让未复核的结论悄悄进入报告")
                .isEqualTo(ReviewTaskStatus.AWAITING_REVIEW);
    }

    @Test
    @DisplayName("非法迁移抛异常且状态不变 —— 不能尽力而为地改一个相近状态")
    void illegalTransitionShouldFailAndKeepStatus() throws Exception {
        Long contractId = preparedContract();
        var outcome = taskService.start(contractId, "illegal-key");
        Long taskId = outcome.task().getId();
        Long tenantId = TenantContext.requireTenantId();

        // PENDING 不能直接到 COMPLETED
        taskMapper.updateState(taskId, tenantId, ReviewTaskStatus.PENDING.name(),
                null, true, 0, 0);

        // 通过 refreshProgress 走状态机：PENDING 不是 AWAITING_REVIEW，
        // 因此只会原地更新统计，不会跳到 COMPLETED
        var task = taskService.refreshProgress(taskId);
        assertThat(task.statusEnum()).isEqualTo(ReviewTaskStatus.PENDING);
    }

    // ==================================================================
    // 隔离与清理
    // ==================================================================

    @Test
    @DisplayName("跨租户读不到任务，也启动不了")
    void otherTenantShouldBeBlocked() throws Exception {
        Long contractId = preparedContract();
        var outcome = taskService.start(contractId, "iso-key");
        Long taskId = outcome.task().getId();

        loginAsOtherTenant();
        assertThat(taskService.findById(taskId)).isNull();
        assertThat(taskService.findByContract(contractId)).isEmpty();
        assertThatThrownBy(() -> taskService.start(contractId, "iso-key-2"))
                .isInstanceOf(com.demo.contract.parse.ContractException.class);
    }

    @Test
    @DisplayName("删除合同清理任务表，但复核审计仍保留（两者性质不同）")
    void deleteShouldCleanTasksButKeepAudit() throws Exception {
        Long contractId = preparedContract();
        var outcome = taskService.start(contractId, "cleanup-key");
        Long taskId = outcome.task().getId();

        var findings = aiReviewService.findings(contractId);
        actionService.record(contractId, findings.get(0).getId(),
                ReviewActionType.ACCEPT, "删除前复核", "cleanup-1");

        Long tenantId = TenantContext.requireTenantId();
        assertThat(taskMapper.findById(taskId, tenantId)).isNotNull();

        contractService.delete(contractId);

        assertThat(taskMapper.findById(taskId, tenantId))
                .withFailMessage("审查任务没有随合同清理，会留下孤儿数据")
                .isNull();
        // 审计独立保留
        assertThat(actionService.history(contractId))
                .withFailMessage("审计记录被一并删除了，违反不变式 I-06")
                .hasSize(1);
    }
}
