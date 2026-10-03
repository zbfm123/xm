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
    private final ContractParsingService parsingService;

    public ContractController(ContractService contractService,
                              ContractParsingService parsingService) {
        this.contractService = contractService;
        this.parsingService = parsingService;
    }

    /** 上传合同。同文件重复上传返回已有记录并置 {@code idempotent=true}。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "title", required = false) String title) {
        return ResponseEntity.ok(contractService.upload(file, title));
    }

    /**
     * 解析合同文本。
     *
     * <p>返回 200 + 结果体，即使解析失败（例如加密 PDF）——因为这是<b>业务结果</b>，
     * 不是请求错误。用 4xx 表达"你的 PDF 加密了"会让前端把业务分支和错误分支混在一起。
     * 真正的请求错误（合同不存在、未登录）仍然用 4xx。
     */
    @PostMapping("/{id}/parse")
    public ResponseEntity<ContractParsingService.ParseOutcome> parse(@PathVariable("id") Long id) {
        return ResponseEntity.ok(parsingService.parse(id));
    }

    /** 读取归一化后的正文，供人工查看与核对证据位置。 */
    @GetMapping("/{id}/text")
    public ResponseEntity<Map<String, Object>> text(@PathVariable("id") Long id) {
        var contractText = parsingService.requireText(id);
        return ResponseEntity.ok(Map.of(
                "contractId", id,
                "text", contractText.getText(),
                "textHash", contractText.getTextHash(),
                "length", contractText.getText().length(),
                "noExtractableText", contractText.isNoExtractableText()
        ));
    }

    /** 分页列表，支持关键字与状态筛选。 */
    @GetMapping
    public ResponseEntity<PageResult<ContractSummary>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
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
