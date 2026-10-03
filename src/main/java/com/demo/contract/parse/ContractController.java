package com.demo.contract.parse;

import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.dto.ContractSummary;
import com.demo.contract.parse.dto.PageResult;
import com.demo.contract.parse.dto.UploadResult;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 合同接口：上传、列表、详情、下载、删除。
 *
 * <p>全部需要登录（未在 {@code SecurityConfig} 白名单中），
 * 且租户范围由 {@code ContractService} 内部从上下文取，**接口不接收租户参数**。
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    /** 上传合同。同文件重复上传返回已有记录并置 {@code idempotent=true}。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "title", required = false) String title) {
        return ResponseEntity.ok(contractService.upload(file, title));
    }

    /** 分页列表，支持关键字与状态筛选。 */
    @GetMapping
    public ResponseEntity<PageResult<ContractSummary>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(contractService.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("id") Long id) {
        Contract c = contractService.get(id);
        // 只暴露必要字段：storagePath 与哈希属于内部实现，不给前端
        return ResponseEntity.ok(Map.of(
                "id", c.getId(),
                "title", c.getTitle(),
                "originalFilename", c.getOriginalFilename(),
                "fileSize", c.getFileSize(),
                "status", c.getStatus().name(),
                "hasText", c.getTextHash() != null,
                "createdAt", c.getCreatedAt() == null ? "" : c.getCreatedAt().toString()
        ));
    }

    /** 下载原始文件。 */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> download(@PathVariable("id") Long id) {
        Contract contract = contractService.get(id);
        InputStream in = contractService.openFile(id);

        String filename = URLEncoder.encode(contract.getOriginalFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
    }

    /**
     * 删除合同。
     *
     * <p>返回 204 且**幂等**：删除不存在的合同同样返回 204。
     * 理由与登出相同——用户的目标（让它消失）已经达成。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        contractService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
