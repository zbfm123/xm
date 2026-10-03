package com.demo.contract.review;

import com.demo.contract.aireview.AiReviewService;
import com.demo.contract.aireview.mapper.AiFindingMapper;
import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiErrorCode;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.parse.ContractException;
import com.demo.contract.parse.ParseErrorCode;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.mapper.ContractMapper;
import com.demo.contract.review.domain.ReviewTaskRow;
import com.demo.contract.review.domain.ReviewTaskStatus;
import com.demo.contract.review.mapper.ReviewTaskMapper;
import com.demo.contract.rule.RuleCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 审查任务状态机（T-017）。
 *
 * <p>把各模块串成一条可重入、幂等的流程，并管住三件事：
 * <ol>
 *   <li><b>幂等</b>——同一幂等键重复提交返回同一个 taskId</li>
 *   <li><b>迁移合法性</b>——非法迁移明确失败，状态不变</li>
 *   <li><b>降级</b>——AI 不可用时<b>继续走人工复核</b>，而不是把整个任务判死</li>
 * </ol>
 *
 * <h3>⚠️ 本类最重要的一条设计：AI 失败不等于任务失败</h3>
 *
 * <p>AI 通道抛 {@code AI_UNAVAILABLE} 时，任务进入 {@link ReviewTaskStatus#AI_UNAVAILABLE}，
 * 然后<b>照常推进到待人工复核</b>。规则结论、要素抽取的结果都还在，
 * 人工可以看着这些继续工作。
 *
 * <p>如果把 AI 失败当成任务终态，就等于"AI 挂了整份审查就废了"——
 * 直接违反不变式 **I-04**。
 *
 * <p>注意区分两件事：
 * <ul>
 *   <li>{@code AI_UNAVAILABLE}（<b>降级</b>）——通道没配、Key 缺失。这是<b>预期内的配置状态</b></li>
 *   <li>其它 {@code AiCallException}（超时/限流/schema 非法）——这是<b>调用失败</b>，
 *       任务同样降级但理由不同，因此 {@code status_reason} 要写清楚是哪一种</li>
 * </ul>
 */
@Service
public class ReviewTaskService {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskService.class);

    private final ReviewTaskMapper taskMapper;
    private final ContractMapper contractMapper;
    private final RuleCheckService ruleCheckService;
    private final ElementExtractionService extractionService;
    private final AiReviewService aiReviewService;
    private final AiFindingMapper aiFindingMapper;

    public ReviewTaskService(ReviewTaskMapper taskMapper,
                             ContractMapper contractMapper,
                             RuleCheckService ruleCheckService,
                             ElementExtractionService extractionService,
                             AiReviewService aiReviewService,
                             AiFindingMapper aiFindingMapper) {
        this.taskMapper = taskMapper;
        this.contractMapper = contractMapper;
        this.ruleCheckService = ruleCheckService;
        this.extractionService = extractionService;
        this.aiReviewService = aiReviewService;
        this.aiFindingMapper = aiFindingMapper;
    }

    /**
     * 启动一次审查任务。
     *
     * <p><b>幂等</b>：同一个 {@code idempotencyKey} 重复调用返回同一个任务，
     * 不会重跑流程。这样用户双击"开始审查"不会产生两套结论。
     *
     * @param idempotencyKey 调用方生成的幂等键，必填
     */
    @Transactional
    public TaskOutcome start(Long contractId, String idempotencyKey) {
        Long tenantId = TenantContext.requireTenantId();
        CurrentUser user = requireCurrentUser();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "启动审查任务必须提供幂等键：没有它，双击就会建出两个任务、两套结论");
        }

        // ---- 幂等：命中则直接返回既有任务，不重跑 ----
        ReviewTaskRow existing = taskMapper.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) {
            log.info("审查任务命中幂等，未重跑流程: taskId={} status={}",
                    existing.getId(), existing.getStatus());
            return new TaskOutcome(existing, true, "命中等价请求，返回既有任务（未重新执行）");
        }

        Contract contract = contractMapper.findByIdAndTenant(contractId, tenantId);
        if (contract == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }

        // ---- 创建任务 ----
        ReviewTaskRow task = new ReviewTaskRow(tenantId, contractId, idempotencyKey,
                ReviewTaskStatus.PENDING.name(), user.getUserId());
        taskMapper.insert(task);

        // PENDING -> IN_PROGRESS
        moveTo(task, ReviewTaskStatus.IN_PROGRESS, null, true, 0, 0);

        // ---- 规则校验（不联网，AI 挂了也必须能跑） ----
        // 这一步刻意放在最前面：它是"AI 不可用时的底线能力"，
        // 因此不能因为后面的 AI 失败而被跳过。
        int ruleTotal;
        try {
            ruleTotal = ruleCheckService.check(contractId).total();
            log.info("任务 {} 规则校验完成，共 {} 条结论", task.getId(), ruleTotal);
        } catch (RuntimeException e) {
            // 规则校验失败是真正的异常（不是降级），任务退回取消并向上抛
            moveTo(task, ReviewTaskStatus.CANCELLED,
                    "规则校验失败：" + e.getMessage(), true, 0, 0);
            throw e;
        }

        // ---- 要素抽取与 AI 审查（可降级） ----
        boolean aiAvailable = true;
        String degradeReason = null;
        int totalFindings = 0;

        try {
            extractionService.extract(contractId);
            var review = aiReviewService.review(contractId);
            totalFindings = review.total();
        } catch (AiCallException e) {
            aiAvailable = false;
            degradeReason = describeDegrade(e);
            log.warn("AI 通道降级，任务继续进入人工复核: taskId={} code={} 原因={}",
                    task.getId(), e.getCode(), degradeReason);
        }

        if (!aiAvailable) {
            // ⚠️ 关键：降级后不是终态，而是继续推进到待人工复核。
            // AI_UNAVAILABLE -> AWAITING_REVIEW 这条边就是 I-04 的落点。
            moveTo(task, ReviewTaskStatus.AI_UNAVAILABLE, degradeReason, false, 0, 0);
        }

        moveTo(task, ReviewTaskStatus.AWAITING_REVIEW,
                degradeReason, aiAvailable, totalFindings, 0);

        String message = aiAvailable
                ? "审查完成，等待人工复核"
                : "AI 通道不可用，已降级：规则结论与要素仍可用于人工复核";

        return new TaskOutcome(task, false, message);
    }

    /** 读取任务。跨租户读不到（返回 null，由调用方决定如何响应）。 */
    @Transactional(readOnly = true)
    public ReviewTaskRow findById(Long taskId) {
        Long tenantId = TenantContext.requireTenantId();
        return taskMapper.findById(taskId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<ReviewTaskRow> findByContract(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return taskMapper.findByContract(contractId, tenantId);
    }

    /**
     * 把降级态的任务推进到待人工复核。
     *
     * <p><b>这是不变式 I-04 的显式出口。</b> AI 不可用不是终态——
     * 用户看完规则结论与要素抽取的结果后，随时可以让流程继续走人工复核。
     *
     * <p>为什么单独做一个方法而不是塞进 refreshProgress：
     * 两者语义完全不同。refreshProgress 只更新进度统计，
     * 不该有"把任务从降级态救回来"这种副作用——
     * **状态推进应当是显式可观察的动作，而不是某个"顺手"操作的副产品。**
     */
    @Transactional
    public ReviewTaskRow proceedAfterDegrade(Long taskId) {
        Long tenantId = TenantContext.requireTenantId();
        ReviewTaskRow task = taskMapper.findById(taskId, tenantId);
        if (task == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "审查任务不存在");
        }

        ReviewTaskStatus current = task.statusEnum();
        if (current == ReviewTaskStatus.AWAITING_REVIEW) {
            return task;
        }
        if (current != ReviewTaskStatus.AI_UNAVAILABLE) {
            throw new IllegalStateException(String.format(
                    "只有降级态(AI_UNAVAILABLE)才能继续进入人工复核，当前为 %s(%s)",
                    current, current.displayName()));
        }

        int reviewed = aiFindingMapper.countReviewed(task.getContractId(), tenantId);
        moveTo(task, ReviewTaskStatus.AWAITING_REVIEW,
                task.getStatusReason(), task.isAiAvailable(), task.getTotalFindings(), reviewed);

        log.info("降级任务继续进入人工复核: taskId={} contractId={} 规则与要素结论保留",
                taskId, task.getContractId());
        return task;
    }
    /**
     * 根据当前已复核条数刷新任务进度；全部复核完则置为已完成。
     *
     * <p>由复核动作记录后调用。**这不是状态机之外的后门**——
     * 它走的是同一套 {@link ReviewTaskStatus#canMoveTo} 校验。
     */
    @Transactional
    public ReviewTaskRow refreshProgress(Long taskId) {
        Long tenantId = TenantContext.requireTenantId();
        ReviewTaskRow task = taskMapper.findById(taskId, tenantId);
        if (task == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "审查任务不存在");
        }

        int reviewed = aiFindingMapper.countReviewed(task.getContractId(), tenantId);
        int total = task.getTotalFindings();

        ReviewTaskStatus current = task.statusEnum();
        if (current == null) {
            throw new IllegalStateException("任务状态值非法: " + task.getStatus());
        }

        // 还没到待复核阶段就不动它
        if (current != ReviewTaskStatus.AWAITING_REVIEW) {
            moveTo(task, current, task.getStatusReason(), task.isAiAvailable(), total, reviewed);
            return task;
        }

        if (total > 0 && reviewed >= total) {
            // ⚠️ 这里刻意**不**自行拆分：状态机没有 AWAITING_REVIEW -> COMPLETED
            // 之外的路。确实全部复核完了才推进。
            moveTo(task, ReviewTaskStatus.COMPLETED,
                    "全部 " + total + " 条候选结论已人工复核完毕", task.isAiAvailable(), total, reviewed);
        } else {
            moveTo(task, ReviewTaskStatus.AWAITING_REVIEW,
                    null, task.isAiAvailable(), total, reviewed);
        }
        return task;
    }

    // ==================================================================

    /**
     * 执行一次状态迁移。
     *
     * <p>非法迁移抛 {@link IllegalStateException} 并<b>保持状态不变</b>——
     * 不能"尽力而为地改一个相近的状态"，那会让状态机名存实亡。
     */
    private void moveTo(ReviewTaskRow task, ReviewTaskStatus target, String reason,
                        boolean aiAvailable, int totalFindings, int reviewedCount) {
        ReviewTaskStatus current = task.statusEnum();
        if (current == null) {
            throw new IllegalStateException("任务状态值非法: " + task.getStatus());
        }

        // 同一个状态不算迁移：允许用它更新统计字段（如 reviewedCount）
        if (current != target && !current.canMoveTo(target)) {
            throw new IllegalStateException(String.format(
                    "非法状态迁移：%s(%s) -> %s。允许的目标：%s",
                    current, current.displayName(), target, current.allowedTargets()));
        }

        int updated = taskMapper.updateState(task.getId(), task.getTenantId(),
                target.name(), reason, aiAvailable, totalFindings, reviewedCount);
        if (updated == 0) {
            throw new IllegalStateException(
                    "状态更新影响 0 行，任务可能已被删除: taskId=" + task.getId());
        }

        // 同步内存对象，便于调用方读到最新状态
        task.setStatus(target.name());
        task.setStatusReason(reason);
        task.setAiAvailable(aiAvailable);
        task.setTotalFindings(totalFindings);
        task.setReviewedCount(reviewedCount);
    }

    private CurrentUser requireCurrentUser() {
        CurrentUser user = CurrentUser.get();
        if (user == null) {
            throw new IllegalStateException("启动审查任务必须有操作人");
        }
        return user;
    }

    /**
     * 把降级原因写清楚。
     *
     * <p>区分"未启用"与"调用失败"很重要：前者是配置问题（改开关即可），
     * 后者是运行时问题（要查网络或额度）。混成一句"AI 不可用"会让排查无路可走。
     */
    private String describeDegrade(AiCallException e) {
        if (e.getCode() == AiErrorCode.AI_UNAVAILABLE) {
            return "AI 通道未启用（app.ai.enabled=false 或未配置 API Key）。"
                    + "这是预期内的降级状态：规则结论与要素抽取均已保留，可继续人工复核";
        }
        if (e.getCode() == AiErrorCode.BUDGET_EXCEEDED
                || e.getCode() == AiErrorCode.CALL_LIMIT_EXCEEDED) {
            return "AI 调用被限额拒绝（" + e.getCode() + "）：" + e.getMessage()
                    + "。规则结论与要素抽取均已保留，可继续人工复核";
        }
        return "AI 调用失败（" + e.getCode() + "）：" + e.getMessage()
                + "。规则结论与要素抽取均已保留，可继续人工复核";
    }

    /**
     * 任务启动结果。
     *
     * @param idempotentHit 是否命中了幂等（true 表示没有重新执行流程）
     */
    public record TaskOutcome(
            ReviewTaskRow task,
            boolean idempotentHit,
            String message
    ) {
    }

}
