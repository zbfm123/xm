package com.demo.contract.review;

import com.demo.contract.review.audit.ReviewActionHasher.ChainVerification;
import com.demo.contract.review.domain.ReviewActionRow;
import com.demo.contract.review.domain.ReviewActionType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工复核接口（T-018）。
 *
 * <p>只有"追加动作"与"查询历史"两类，<b>没有"修改动作"与"删除动作"</b>。
 * 撤销一个动作的正确方式是追加一条反向动作。
 */
@RestController
@RequestMapping("/api/contracts/{id}/reviews")
public class ReviewActionController {

    private final ReviewActionService service;

    public ReviewActionController(ReviewActionService service) {
        this.service = service;
    }

    /** 复核请求体。 */
    public record ReviewRequest(
            Long findingId,
            ReviewActionType action,
            String reason,
            String idempotencyKey
    ) {
    }

    /**
     * 记录一次人工复核动作。
     *
     * <p>{@code idempotencyKey} 必填：审计日志写错后无法删除，
     * 因此重复提交必须在入口处挡住，不能靠事后清理。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> record(@PathVariable("id") Long contractId,
                                                     @RequestBody ReviewRequest request) {
        if (request.action() == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        ReviewActionRow row = service.record(contractId, request.findingId(),
                request.action(), request.reason(), request.idempotencyKey());
        return ResponseEntity.ok(toView(row));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> history(@PathVariable("id") Long contractId) {
        return ResponseEntity.ok(service.history(contractId).stream()
                .map(ReviewActionController::toView).toList());
    }

    /**
     * 校验哈希链完整性。
     *
     * <p>暴露成接口而不是只留在日志里：**"能自证历史未被改动"这件事必须可被查验**，
     * 否则它只是一个无法验证的声明。
     */
    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable("id") Long contractId) {
        ChainVerification v = service.verifyChain(contractId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intact", v.intact());
        body.put("checked", v.checked());
        body.put("brokenAt", v.brokenAt());
        body.put("reason", v.reason());
        body.put("explanation", v.intact()
                ? "哈希链完好：所有复核记录的内容与顺序均未被改动"
                : "哈希链已断： " + v.reason());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> toView(ReviewActionRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("findingId", r.getFindingId());
        m.put("action", r.getAction());
        m.put("reason", r.getReason());
        m.put("operatorName", r.getOperatorName());
        m.put("previousStatus", r.getPreviousStatus());
        m.put("newStatus", r.getNewStatus());
        m.put("idempotencyKey", r.getIdempotencyKey());
        // 哈希前缀暴露出来：便于人工比对与排查，完整值意义不大
        m.put("recordHash", r.getRecordHash());
        m.put("previousHash", r.getPreviousHash());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return m;
    }
}
