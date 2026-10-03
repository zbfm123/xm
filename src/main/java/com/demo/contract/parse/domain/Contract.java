package com.demo.contract.parse.domain;

import java.time.LocalDateTime;

/**
 * 合同主记录，对应 {@code contract} 表。
 *
 * <p>三个哈希/路径字段的分工，容易混：
 * <ul>
 *   <li>{@code fileHash} —— 上传文件<b>字节</b>的 SHA-256。用于存储去重与"同一文件重复上传"的幂等。</li>
 *   <li>{@code textHash} —— 归一化后<b>文本</b>的 SHA-256。用于 AI 结果缓存的键，也是"同一内容不重复调模型"的依据。</li>
 *   <li>{@code storagePath} —— 本地磁盘路径（决策 D-09）。</li>
 * </ul>
 *
 * <p>为什么两个哈希都要：同样内容的合同可能是不同文件（重新导出的 PDF 字节不同但文本相同），
 * 反之同一文件重新上传字节与文本都相同。<b>幂等看文件字节，缓存看文本内容。</b>
 */
public class Contract {

    private Long id;
    private Long tenantId;
    private String title;
    private String originalFilename;
    private String fileHash;
    private String textHash;
    private String storagePath;
    private Long fileSize;
    private ContractStatus status;
    private boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getTextHash() {
        return textHash;
    }

    public void setTextHash(String textHash) {
        this.textHash = textHash;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public void setStatus(ContractStatus status) {
        this.status = status;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
