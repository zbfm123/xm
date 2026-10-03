package com.demo.contract.review.domain;

import java.time.LocalDateTime;

/** 审查任务行对象，对应 {@code review_task} 表。 */
public class ReviewTaskRow {

    private Long id;
    private Long tenantId;
    private Long contractId;
    private String idempotencyKey;
    private String status;
    private String statusReason;
    private boolean aiAvailable;
    private int totalFindings;
    private int reviewedCount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ReviewTaskRow() {
    }

    public ReviewTaskRow(Long tenantId, Long contractId, String idempotencyKey,
                         String status, Long createdBy) {
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.createdBy = createdBy;
        this.aiAvailable = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public boolean isAiAvailable() { return aiAvailable; }
    public void setAiAvailable(boolean aiAvailable) { this.aiAvailable = aiAvailable; }
    public int getTotalFindings() { return totalFindings; }
    public void setTotalFindings(int totalFindings) { this.totalFindings = totalFindings; }
    public int getReviewedCount() { return reviewedCount; }
    public void setReviewedCount(int reviewedCount) { this.reviewedCount = reviewedCount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 状态枚举视图；库中值非法时返回 null 由调用方判断。 */
    public ReviewTaskStatus statusEnum() {
        return ReviewTaskStatus.from(status);
    }
}
