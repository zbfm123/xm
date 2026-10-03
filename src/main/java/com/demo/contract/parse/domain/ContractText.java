package com.demo.contract.parse.domain;

import java.time.LocalDateTime;

/**
 * 合同正文，对应 {@code contract_text} 表。
 *
 * <p>关键字段是 {@code offsetMap}：归一化会改变字符长度（全角转半角、空白折叠、去页眉页脚），
 * 而下游（[extract] 与 [ai-review]）的证据区间必须能<b>回到原文</b>。
 * 因此这里保存"归一化坐标 → 原文坐标"的映射，而不是只存一段洗干净的文本。
 *
 * <p>当前实现尚未填充该字段（属于 T-009）。此处先把列与语义定下来，
 * 避免将来改表结构——用 {@code schema.sql} 的代价就是改表要手工 ALTER。
 */
public class ContractText {

    private Long id;
    private Long tenantId;
    private Long contractId;
    /** 归一化后的文本。 */
    private String text;
    /** 归一化文本的 SHA-256，AI 结果缓存的键。 */
    private String textHash;
    /** 归一化坐标 → 原文坐标的映射（JSON），T-009 填充。 */
    private String offsetMap;
    /** 是否命中了"疑似扫描件"（提取结果为空）。 */
    private boolean noExtractableText;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getContractId() {
        return contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTextHash() {
        return textHash;
    }

    public void setTextHash(String textHash) {
        this.textHash = textHash;
    }

    public String getOffsetMap() {
        return offsetMap;
    }

    public void setOffsetMap(String offsetMap) {
        this.offsetMap = offsetMap;
    }

    public boolean isNoExtractableText() {
        return noExtractableText;
    }

    public void setNoExtractableText(boolean noExtractableText) {
        this.noExtractableText = noExtractableText;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
