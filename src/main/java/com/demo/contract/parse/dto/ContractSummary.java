package com.demo.contract.parse.dto;

import com.demo.contract.parse.domain.Contract;

import java.time.LocalDateTime;

/**
 * 合同列表项投影。
 *
 * <p>刻意不返回 {@code storagePath} 与 {@code fileHash}：
 * 存储路径属于内部实现细节，暴露它等于告诉别人文件放在哪；
 * 哈希对前端也没有用途。**能不给的就不给。**
 */
public record ContractSummary(
        Long id,
        String title,
        String originalFilename,
        Long fileSize,
        String status,
        LocalDateTime createdAt
) {
    public static ContractSummary from(Contract c) {
        return new ContractSummary(
                c.getId(), c.getTitle(), c.getOriginalFilename(),
                c.getFileSize(), c.getStatus().name(), c.getCreatedAt());
    }
}
