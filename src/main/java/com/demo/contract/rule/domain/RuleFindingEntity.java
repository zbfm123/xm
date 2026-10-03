package com.demo.contract.rule.domain;

import java.time.LocalDateTime;

/**
 * 规则结论的持久化实体，对应 {@code rule_finding} 表。
 *
 * <p>字段与 {@link RuleFinding} 一致，但多带了 {@code id} / {@code tenantId} /
 * {@code contractId} / {@code checkedAt}。
 */
public class RuleFindingEntity {

    private Long id;
    private Long tenantId;
    private Long contractId;
    private String ruleCode;
    private String ruleName;
    private String severity;
    private String result;
    private String evidence;
    private Integer charStart;
    private Integer charEnd;
    private String detail;
    private String errorMessage;
    private LocalDateTime checkedAt;

    /** 由引擎结论构造实体。 */
    public static RuleFindingEntity from(Long tenantId, Long contractId, RuleFinding f) {
        RuleFindingEntity e = new RuleFindingEntity();
        e.tenantId = tenantId;
        e.contractId = contractId;
        e.ruleCode = f.ruleCode();
        e.ruleName = f.ruleName();
        e.severity = f.severity().name();
        e.result = f.result().name();
        // 列宽限制：超长时截断，避免整条写入失败
        e.evidence = truncate(f.evidence(), 500);
        e.charStart = f.charStart();
        e.charEnd = f.charEnd();
        e.detail = truncate(f.detail(), 1000);
        e.errorMessage = truncate(f.errorMessage(), 500);
        return e;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public RuleResult resultEnum() {
        return RuleResult.valueOf(result);
    }

    public Severity severityEnum() {
        return Severity.valueOf(severity);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public Integer getCharStart() { return charStart; }
    public void setCharStart(Integer charStart) { this.charStart = charStart; }
    public Integer getCharEnd() { return charEnd; }
    public void setCharEnd(Integer charEnd) { this.charEnd = charEnd; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}
