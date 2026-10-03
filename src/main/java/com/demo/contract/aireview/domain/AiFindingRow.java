package com.demo.contract.aireview.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** AI 风险结论（候选），对应 {@code ai_finding} 表。 */
public class AiFindingRow {

    private Long id;
    private Long tenantId;
    private Long contractId;
    private String riskType;
    private String quote;
    private Integer charStart;
    private Integer charEnd;
    private BigDecimal confidence;
    private String matchLevel;
    private String status;
    private String statusReason;
    private String modelVersion;
    private String promptVersion;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getRiskType() { return riskType; }
    public void setRiskType(String riskType) { this.riskType = riskType; }
    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }
    public Integer getCharStart() { return charStart; }
    public void setCharStart(Integer charStart) { this.charStart = charStart; }
    public Integer getCharEnd() { return charEnd; }
    public void setCharEnd(Integer charEnd) { this.charEnd = charEnd; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getMatchLevel() { return matchLevel; }
    public void setMatchLevel(String matchLevel) { this.matchLevel = matchLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
