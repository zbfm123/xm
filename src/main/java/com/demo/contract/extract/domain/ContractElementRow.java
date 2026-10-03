package com.demo.contract.extract.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 抽取到的合同要素，对应 {@code contract_element} 表。 */
public class ContractElementRow {

    private Long id;
    private Long tenantId;
    private Long contractId;
    private String fieldKey;
    private String elementValue;
    private String quote;
    private Integer charStart;
    private Integer charEnd;
    private BigDecimal confidence;
    private String matchLevel;
    private String status;
    private String statusReason;
    private String source;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }
    public String getElementValue() { return elementValue; }
    public void setElementValue(String elementValue) { this.elementValue = elementValue; }
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
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
