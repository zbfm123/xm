package com.demo.contract.review;

import com.demo.contract.aireview.domain.AiFindingRow;
import com.demo.contract.aireview.mapper.AiFindingMapper;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.parse.ContractException;
import com.demo.contract.parse.ParseErrorCode;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.mapper.ContractMapper;
import com.demo.contract.review.audit.ReviewActionHasher;
import com.demo.contract.review.audit.ReviewActionHasher.ChainVerification;
import com.demo.contract.review.domain.ReviewActionRow;
import com.demo.contract.review.domain.ReviewActionType;
import com.demo.contract.review.mapper.ReviewActionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 人工复核记录服务（T-018）。
 *
 * <p>本类实现不变式 **I-06：复核痕迹只追加**。三条纪律：
 *
 * <ol>
 *   <li><b>只写不改</b>——没有任何更新或删除路径。撤销一个动作的正确做法是
 *       <b>追加一条反向动作</b>，让历史保留"曾经采纳又被驳回"这个事实。</li>
 *   <li><b>幂等</b>——同一幂等键重放不会产生第二条记录。
 *       没有这一条，网络重试或用户双击就会在审计日志里留下重复动作，
 *       而审计日志一旦写错就无法删除。</li>
 *   <li><b>哈希成链</b>——每条记录包含前一条的哈希，事后改动可被检测。</li>
 * </ol>
 *
 * <p>⚠️ 本类的职责是<b>记录事实</b>，不是判断该不该复核——
 * 后者属于权限与流程（T-017）。
 */
@Service
public class ReviewActionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewActionService.class);

    private final ReviewActionMapper actionMapper;
    private final AiFindingMapper findingMapper;
    private final ContractMapper contractMapper;

    public ReviewActionService(ReviewActionMapper actionMapper,
                               AiFindingMapper findingMapper,
                               ContractMapper contractMapper) {
        this.actionMapper = actionMapper;
        this.findingMapper = findingMapper;
        this.contractMapper = contractMapper;
    }

    /**
     * 记录一次复核动作。
     *
     * @param findingId      被复核的 AI 结论 id；合同级动作（如 CONFIRM_NO_RISK）传 null
     * @param idempotencyKey 调用方提供的幂等键；同样的键只产生一条记录
     * @param reason         复核理由；ACCEPT/REJECT 时应当给出，否则事后无法解释
     */
    @Transactional
    public ReviewActionRow record(Long contractId, Long findingId,
                                  ReviewActionType action, String reason,
                                  String idempotencyKey) {

        Long tenantId = TenantContext.requireTenantId();
        CurrentUser operator = requireCurrentUser();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "复核动作必须提供幂等键：审计日志写错后无法删除，重复记录不能靠事后清理");
        }

        // ---- 幂等：同一键直接返回既有记录，不写第二条 ----
        ReviewActionRow existing = actionMapper.findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null) {
            log.info("复核动作命中幂等，未产生新记录: key={} actionId={}",
                    idempotencyKey, existing.getId());
            return existing;
        }

        Contract contract = contractMapper.findByIdAndTenant(contractId, tenantId);
        if (contract == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }

        // ---- 确定动作前后的状态 ----
        String previousStatus = null;
        if (findingId != null) {
            AiFindingRow finding = findOwnedFinding(findingId, contractId, tenantId);
            previousStatus = finding.getStatus();
        } else if (action != ReviewActionType.CONFIRM_NO_RISK) {
            // 除了"确认无风险"这类合同级动作，其他都必须指向具体结论。
            // 允许一个不指向任何结论的 ACCEPT 会让报告出现无法追溯的"已采纳"。
            throw new IllegalArgumentException(
                    "动作 " + action + " 必须指向一条具体的 AI 结论（findingId 不能为空）");
        }

        String newStatus = mapStatus(action);

        // ---- 构造记录并计算哈希 ----
        ReviewActionRow previous = actionMapper.findLast(contractId, tenantId);
        String previousHash = previous == null
                ? ReviewActionHasher.GENESIS_HASH : previous.getRecordHash();

        ReviewActionRow row = new ReviewActionRow(
                tenantId, contractId, findingId, idempotencyKey,
                action.name(), reason,
                operator.getUserId(), operator.getUsername(),
                previousStatus, newStatus,
                null,                       // recordHash 先占位，算完再构造
                previousHash);

        String recordHash = ReviewActionHasher.compute(row);
        ReviewActionRow toInsert = new ReviewActionRow(
                tenantId, contractId, findingId, idempotencyKey,
                action.name(), reason,
                operator.getUserId(), operator.getUsername(),
                previousStatus, newStatus,
                recordHash, previousHash);

        actionMapper.insert(toInsert);

        // ---- 同步结论状态 ----
        // 说明：这里更新的是 ai_finding.status（机器结论的状态），
        // **不是** review_action 本身。审计记录永远不改。
        if (findingId != null) {
            updateFindingStatus(findingId, contractId, tenantId, newStatus, reason);
        }

        log.info("复核动作已记录: contractId={} findingId={} action={} operator={} "
                        + "{}→{} hash={}",
                contractId, findingId, action, operator.getUsername(),
                previousStatus, newStatus, recordHash.substring(0, 12) + "…");

        return toInsert;
    }

    /** 读取某合同的复核历史，按时间正序。 */
    @Transactional(readOnly = true)
    public List<ReviewActionRow> history(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return actionMapper.findByContract(contractId, tenantId);
    }

    /** 读取某条结论的复核历史。 */
    @Transactional(readOnly = true)
    public List<ReviewActionRow> historyOfFinding(Long findingId) {
        Long tenantId = TenantContext.requireTenantId();
        return actionMapper.findByFinding(findingId, tenantId);
    }

    /**
     * 校验整条哈希链是否完好。
     *
     * <p>校验两件事：
     * <ol>
     *   <li>每条记录的 {@code record_hash} 与按字段重算的结果一致（内容未被改动）</li>
     *   <li>每条记录的 {@code previous_hash} 等于前一条的 {@code record_hash}（顺序未被改动）</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        List<ReviewActionRow> rows = actionMapper.findByContract(contractId, tenantId);

        String expectedPrevious = ReviewActionHasher.GENESIS_HASH;
        int checked = 0;

        for (ReviewActionRow row : rows) {
            checked++;

            if (!expectedPrevious.equals(row.getPreviousHash())) {
                return ChainVerification.broken(checked, row.getId(),
                        "前驱哈希不匹配：记录 " + row.getId() + " 的 previous_hash 与上一条的 "
                                + "record_hash 不一致，说明中间有记录被删除或顺序被改动");
            }

            String recomputed = ReviewActionHasher.compute(row);
            if (!recomputed.equals(row.getRecordHash())) {
                return ChainVerification.broken(checked, row.getId(),
                        "记录 " + row.getId() + " 的内容与其哈希不符，该条已被改动");
            }

            expectedPrevious = row.getRecordHash();
        }

        return ChainVerification.ok(checked);
    }

    // ==================================================================

    private CurrentUser requireCurrentUser() {
        CurrentUser user = CurrentUser.get();
        if (user == null) {
            // 审计记录没有操作人是没有意义的——它无法回答"谁做的"
            throw new IllegalStateException("复核动作必须有操作人，当前上下文没有登录用户");
        }
        return user;
    }

    private AiFindingRow findOwnedFinding(Long findingId, Long contractId, Long tenantId) {
        return findingMapper.findByContract(contractId, tenantId).stream()
                .filter(f -> findingId.equals(f.getId()))
                .findFirst()
                .orElseThrow(() -> new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND,
                        "AI 结论不存在或不属于该合同: " + findingId));
    }

    /** 动作 → 结论的新状态。 */
    private String mapStatus(ReviewActionType action) {
        return switch (action) {
            case ACCEPT -> "ACCEPTED";
            case REJECT -> "REJECTED";
            case ESCALATE -> "ESCALATED";
            case NEED_INFO -> "NEED_INFO";
            case CONFIRM_NO_RISK -> "CONFIRMED_NO_RISK";
        };
    }

    /**
     * 更新机器结论的状态。
     *
     * <p>这里用的是 {@code ai_finding} 的更新接口——它是机器结论，
     * 允许随复核结果变化。审计记录本身体现在 {@code review_action} 里，永不变化。
     */
    private void updateFindingStatus(Long findingId, Long contractId, Long tenantId,
                                     String newStatus, String reason) {
        int updated = findingMapper.updateStatus(findingId, contractId, tenantId,
                newStatus, reason == null ? null : "复核：" + reason);
        if (updated == 0) {
            // 结论不存在时不应静默通过：审计记录说"采纳了"，但被采纳的对象不存在
            throw new IllegalStateException(
                    "复核动作指向的 AI 结论不存在或不属于该租户: findingId=" + findingId);
        }
    }
}
