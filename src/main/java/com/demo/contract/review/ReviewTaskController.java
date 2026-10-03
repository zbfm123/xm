package com.demo.contract.review;

import com.demo.contract.review.domain.ReviewTaskRow;
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
 * 审查任务接口（T-017）。
 *
 * <p>任务状态与合同状态是两件事：
 * {@code contract.status} 描述<b>数据</b>状态（已解析/已校验），
 * {@code task.status} 描述<b>流程</b>状态（处理中/待人工复核）。
 */
@RestController
@RequestMapping("/api")
public class ReviewTaskController {

    private final ReviewTaskService taskService;

    public ReviewTaskController(ReviewTaskService taskService) {
        this.taskService = taskService;
    }

    /** 启动任务的请求体。 */
    public record StartRequest(String idempotencyKey) {
    }

    /**
     * 启动一次审查任务。
     *
     * <p>响应里的 {@code idempotentHit} 明确告诉调用方"这次没有重新执行"——
     * 这比默默返回同一个 taskId 更有用：前端可以据此避免显示"刚刚完成"的提示。
     */
    @PostMapping("/contracts/{id}/review-tasks")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("id") Long contractId,
                                                    @RequestBody(required = false) StartRequest request) {
        String key = request == null ? null : request.idempotencyKey();
        var outcome = taskService.start(contractId, key);

        Map<String, Object> body = toView(outcome.task());
        body.put("idempotentHit", outcome.idempotentHit());
        body.put("message", outcome.message());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/review-tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("taskId") Long taskId) {
        ReviewTaskRow task = taskService.findById(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(task));
    }

    @GetMapping("/contracts/{id}/review-tasks")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable("id") Long contractId) {
        return ResponseEntity.ok(taskService.findByContract(contractId).stream()
                .map(ReviewTaskController::toView).toList());
    }

    /**
     * 刷新任务进度（按当前已复核条数决定是否完成）。
     *
     * <p>暴露成接口而不是"内部自动调用"，理由：**状态推进应当是显式可观察的**。
     * 前端复核完最后一条后调用它，就能在响应里看到任务是否已完成。
     */
    @PostMapping("/review-tasks/{taskId}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(toView(taskService.refreshProgress(taskId)));
    }

    /**
     * 降级后继续进入人工复核。
     *
     * <p>这是不变式 I-04 的显式出口：AI 不可用时任务停在
     * {@code AI_UNAVAILABLE}，用户看完规则结论与要素后可以让流程继续。
     */
    @PostMapping("/review-tasks/{taskId}/proceed")
    public ResponseEntity<Map<String, Object>> proceed(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(toView(taskService.proceedAfterDegrade(taskId)));
    }

    private static Map<String, Object> toView(ReviewTaskRow t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.getId());
        m.put("contractId", t.getContractId());
        m.put("status", t.getStatus());
        m.put("statusDisplay", t.statusEnum() == null
                ? t.getStatus() : t.statusEnum().displayName());
        m.put("statusReason", t.getStatusReason());
        m.put("aiAvailable", t.isAiAvailable());
        m.put("totalFindings", t.getTotalFindings());
        m.put("reviewedCount", t.getReviewedCount());
        m.put("terminal", t.statusEnum() != null && t.statusEnum().isTerminal());
        m.put("allowedNextStatuses", t.statusEnum() == null
                ? List.of()
                : t.statusEnum().allowedTargets().stream().map(Enum::name).toList());
        m.put("idempotencyKey", t.getIdempotencyKey());
        m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        m.put("updatedAt", t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
        return m;
    }
}
