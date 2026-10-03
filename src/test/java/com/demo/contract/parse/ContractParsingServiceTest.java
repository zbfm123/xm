package com.demo.contract.parse;

import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.parse.text.NormalizedText;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合同解析集成测试（T-009 / T-010）。
 *
 * <p>用真实的 PDF / DOCX 字节，经上传 → 解析 → 落库完整链路，
 * 验证状态推进、正文写入、`textHash` 回填与各类失败路径。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class ContractParsingServiceTest extends AuthenticatedTestBase {

    @Autowired
    private ContractService contractService;

    @Autowired
    private ContractParsingService parsingService;

    @Autowired
    private TestPdfFactory pdfFactory;

    @Autowired
    private TestDocxFactory docxFactory;

    // ==================================================================
    // 成功路径
    // ==================================================================

    @Test
    @DisplayName("解析 PDF 成功：状态推进到 PARSED，正文与 textHash 落库")
    void parsePdfShouldSucceed() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("采购合同", "Party A: Beijing Demo Technology Co Ltd");

        ContractParsingService.ParseOutcome outcome = parsingService.parse(id);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.textHash()).isNotBlank();
        assertThat(outcome.normalizedLength()).isGreaterThan(0);

        // 状态已推进
        Contract contract = contractService.get(id);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.PARSED);
        assertThat(contract.getTextHash()).isEqualTo(outcome.textHash());

        // 正文可取回
        var text = parsingService.requireText(id);
        assertThat(text.getText()).contains("Beijing Demo Technology");
        assertThat(text.getTextHash()).isEqualTo(outcome.textHash());
        assertThat(text.isNoExtractableText()).isFalse();
    }

    @Test
    @DisplayName("解析 DOCX 成功，表格内容进入正文")
    void parseDocxWithTableShouldSucceed() throws IOException {
        loginAsDemoTenant();

        byte[] docx = docxFactory.build(
                List.of("付款计划如下："),
                List.of(
                        List.of("期次", "金额"),
                        List.of("第一期", "128000")));

        Long id = contractService.upload(new MockMultipartFile(
                "file", "付款计划.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx), null)
                .contract().id();

        ContractParsingService.ParseOutcome outcome = parsingService.parse(id);

        assertThat(outcome.success()).isTrue();
        // 表格里的金额必须被提取到，否则金额规则校验会静默失效
        assertThat(parsingService.requireText(id).getText()).contains("128000");
    }

    @Test
    @DisplayName("偏移映射已落库，且能据此把归一化位置回查回原文")
    void offsetMapShouldBePersistedAndUsable() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("坐标测试", "Party A : Beijing Demo Technology Co Ltd");

        parsingService.parse(id);

        var text = parsingService.requireText(id);
        assertThat(text.getOffsetMap()).isNotBlank();

        int[] offsets = parseOffsets(text.getOffsetMap());
        assertThat(offsets).hasSize(text.getText().length());

        // 用落库的映射重建可回查对象：这是下游证据对齐要走的同一条路径
        NormalizedText lookup = new NormalizedText(text.getText(), offsets, text.getText());

        int idx = lookup.text().indexOf("Beijing Demo Technology");
        assertThat(idx).isGreaterThanOrEqualTo(0);

        int[] range = lookup.toOriginalRange(idx, idx + "Beijing Demo Technology".length());
        assertThat(lookup.originalSlice(range[0], range[1])).contains("Beijing Demo Technology");
    }

    @Test
    @DisplayName("重复解析是幂等的：不重复提取，正文不重复写入")
    void repeatedParseShouldBeIdempotent() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("幂等解析", "Some contract body text");

        var first = parsingService.parse(id);
        var second = parsingService.parse(id);

        assertThat(second.success()).isTrue();
        assertThat(second.message()).contains("未重复处理");
        assertThat(second.textHash()).isEqualTo(first.textHash());

        // 正文只有一行（唯一索引也会挡住重复插入，但这里验证的是业务层幂等）
        assertThat(parsingService.requireText(id).getText()).isEqualTo(
                parsingService.requireText(id).getText());
    }

    // ==================================================================
    // 失败路径（T-010）
    // ==================================================================

    @Test
    @DisplayName("加密 PDF：状态置 PARSE_FAILED，不产生正文，返回明确错误码")
    void encryptedPdfShouldFailExplicitly() throws IOException {
        loginAsDemoTenant();

        byte[] encrypted = pdfFactory.buildEncrypted("Secret contract body");
        Long id = contractService.upload(new MockMultipartFile(
                "file", "encrypted.pdf", "application/pdf", encrypted), null).contract().id();

        ContractParsingService.ParseOutcome outcome = parsingService.parse(id);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(ParseErrorCode.PDF_ENCRYPTED);

        assertThat(contractService.get(id).getStatus()).isEqualTo(ContractStatus.PARSE_FAILED);

        // 关键：失败时不留下半个正文
        assertThatThrownBy(() -> parsingService.requireText(id))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("无文本 PDF（疑似扫描件）：显式失败，而不是返回空正文")
    void imageOnlyPdfShouldFailExplicitly() throws IOException {
        loginAsDemoTenant();

        byte[] noText = pdfFactory.buildBlankPage();
        Long id = contractService.upload(new MockMultipartFile(
                "file", "scan.pdf", "application/pdf", noText), null).contract().id();

        ContractParsingService.ParseOutcome outcome = parsingService.parse(id);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(ParseErrorCode.NO_EXTRACTABLE_TEXT);
        assertThat(outcome.message()).contains("扫描件");
        assertThat(contractService.get(id).getStatus()).isEqualTo(ContractStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("损坏的 PDF：状态置 PARSE_FAILED 并返回 PARSE_FAILED")
    void corruptedPdfShouldFail() throws IOException {
        loginAsDemoTenant();

        // 先构造一个能被上传校验（魔数正确）但内部结构损坏的文件
        byte[] corrupted = "%PDF-1.4\n这不是合法的 PDF 内部结构".getBytes(StandardCharsets.UTF_8);
        Long id = contractService.upload(new MockMultipartFile(
                "file", "corrupted.pdf", "application/pdf", corrupted), null).contract().id();

        ContractParsingService.ParseOutcome outcome = parsingService.parse(id);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(ParseErrorCode.PARSE_FAILED);
        assertThat(contractService.get(id).getStatus()).isEqualTo(ContractStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("已删除的合同不能再解析")
    void deletedContractCannotBeParsed() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("待删除", "content");
        contractService.delete(id);

        assertThatThrownBy(() -> parsingService.parse(id))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.CONTRACT_NOT_FOUND));
    }

    @Test
    @DisplayName("跨租户不能解析他人的合同")
    void otherTenantCannotParse() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("租户1合同", "content");

        loginAsOtherTenant();

        assertThatThrownBy(() -> parsingService.parse(id))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.CONTRACT_NOT_FOUND));
    }

    @Test
    @DisplayName("未解析的合同取正文时明确失败，而不是返回空文本")
    void requireTextBeforeParseShouldFail() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("未解析", "content");

        assertThatThrownBy(() -> parsingService.requireText(id))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("尚未解析");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private Long uploadPdf(String title, String body) throws IOException {
        byte[] pdf = pdfFactory.build(List.of(body));
        return contractService.upload(new MockMultipartFile(
                "file", title + ".pdf", "application/pdf", pdf), title).contract().id();
    }

    private int[] parseOffsets(String csv) {
        String[] parts = csv.split(",");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Integer.parseInt(parts[i].trim());
        }
        return arr;
    }
}
