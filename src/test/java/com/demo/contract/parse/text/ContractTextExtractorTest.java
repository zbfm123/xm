package com.demo.contract.parse.text;

import com.demo.contract.parse.ContractException;
import com.demo.contract.parse.ParseErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文本提取测试。
 *
 * <p>这里<b>构造真实的 PDF / DOCX 文件</b>，而不是喂假字节。
 * 理由：提取层的失败模式（字体、编码、页数、表格）只有真实文件才会触发，
 * 用假的字节流测出来的"通过"没有意义。
 *
 * <p>全部内容为虚构，不含任何真实合同信息。
 */
class ContractTextExtractorTest {

    private final ContractTextExtractor extractor = new ContractTextExtractor();

    // ==================================================================
    // PDF
    // ==================================================================

    @Test
    @DisplayName("从真实 PDF 提取文本，且页数正确")
    void shouldExtractTextFromPdf() throws IOException {
        byte[] pdf = buildPdf(List.of(
                "Party A: Beijing Demo Technology Co Ltd",
                "Party B: Shanghai Sample Trading Co Ltd"));

        ExtractionResult result = extractor.extract(new ByteArrayInputStream(pdf), "contract.pdf");

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.rawText())
                .contains("Beijing Demo Technology")
                .contains("Shanghai Sample Trading");
    }

    @Test
    @DisplayName("多页 PDF 的页数被正确统计（供页眉页脚清理判断）")
    void shouldCountPdfPages() throws IOException {
        byte[] pdf = buildPdf(List.of("page one"), List.of("page two"), List.of("page three"));

        ExtractionResult result = extractor.extract(new ByteArrayInputStream(pdf), "multi.pdf");

        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.rawText()).contains("page one").contains("page three");
    }

    @Test
    @DisplayName("纯图片型 PDF（可打开但无文本）被识别为空，而不是抛出异常")
    void imageOnlyPdfShouldBeReportedAsEmpty() throws IOException {
        // 构造一个只有空白页的 PDF，等价于扫描件
        byte[] pdf = buildPdf(List.of(""));

        ExtractionResult result = extractor.extract(new ByteArrayInputStream(pdf), "scan.pdf");

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.isEmpty())
                .withFailMessage("无文本的 PDF 必须被识别为疑似扫描件，而不是当成空合同继续处理")
                .isTrue();
    }

    @Test
    @DisplayName("损坏的 PDF 抛出 PARSE_FAILED，而不是返回空文本")
    void corruptedPdfShouldThrow() {
        byte[] garbage = "%PDF-1.4\n这不是一个合法的 PDF 结构".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(garbage), "broken.pdf"))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isIn(ParseErrorCode.PARSE_FAILED, ParseErrorCode.MIME_MISMATCH));
    }

    // ==================================================================
    // DOCX
    // ==================================================================

    @Test
    @DisplayName("从真实 DOCX 提取段落文本")
    void shouldExtractParagraphsFromDocx() throws IOException {
        byte[] docx = buildDocx(
                List.of("采购合同", "甲方：北京某某科技有限公司", "乙方：上海某某贸易有限公司"),
                null);

        ExtractionResult result = extractor.extract(new ByteArrayInputStream(docx), "contract.docx");

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.rawText())
                .contains("采购合同")
                .contains("甲方：北京某某科技有限公司")
                .contains("乙方：上海某某贸易有限公司");
    }

    @Test
    @DisplayName("表格内容也被提取——合同里的金额常放在表格中")
    void shouldExtractTableContentFromDocx() throws IOException {
        byte[] docx = buildDocx(
                List.of("付款计划如下："),
                List.of(
                        List.of("期次", "金额", "时间"),
                        List.of("第一期", "128000", "2026-11-01")));

        ExtractionResult result = extractor.extract(new ByteArrayInputStream(docx), "table.docx");

        // 只读段落会静默丢掉金额，这是很容易漏的一点
        assertThat(result.rawText())
                .withFailMessage("表格内容未被提取，合同金额会静默丢失")
                .contains("128000")
                .contains("第一期");
    }

    @Test
    @DisplayName("空白 DOCX 被识别为空")
    void blankDocxShouldBeReportedAsEmpty() throws IOException {
        byte[] docx = buildDocx(List.of("   ", ""), null);

        ExtractionResult result = extractor.extract(new ByteArrayInputStream(docx), "blank.docx");
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("损坏的 DOCX 抛出 PARSE_FAILED")
    void corruptedDocxShouldThrow() {
        // 用 \u0003 而不是 \x03：Java 没有 \x 转义
        byte[] garbage = "PK\u0003\u0004这不是一个合法的 zip 结构".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(garbage), "broken.docx"))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.PARSE_FAILED));
    }

    // ==================================================================
    // 分发
    // ==================================================================

    @Test
    @DisplayName("不支持的扩展名抛出 MIME_MISMATCH")
    void unsupportedExtensionShouldThrow() {
        assertThatThrownBy(() -> extractor.extract(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), "note.txt"))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.MIME_MISMATCH));
    }

    @Test
    @DisplayName("扩展名大小写不敏感")
    void extensionShouldBeCaseInsensitive() throws IOException {
        byte[] pdf = buildPdf(List.of("Case Insensitive Check"));
        ExtractionResult result = extractor.extract(new ByteArrayInputStream(pdf), "CONTRACT.PDF");
        assertThat(result.isEmpty()).isFalse();
    }

    // ==================================================================
    // 提取 + 归一化 串联
    // ==================================================================

    @Test
    @DisplayName("提取后再归一化，坐标仍能回查原文")
    void extractedTextShouldStillMapBackAfterNormalization() throws IOException {
        byte[] pdf = buildPdf(List.of("Party A : Beijing Demo Technology Co Ltd"));

        ExtractionResult extracted = extractor.extract(new ByteArrayInputStream(pdf), "a.pdf");
        NormalizedText normalized = new TextNormalizer().normalize(extracted.rawText(), extracted.pageCount());

        int idx = normalized.text().indexOf("Beijing Demo Technology");
        assertThat(idx).isGreaterThanOrEqualTo(0);

        int[] range = normalized.toOriginalRange(idx, idx + "Beijing Demo Technology".length());
        assertThat(normalized.originalSlice(range[0], range[1])).contains("Beijing Demo Technology");
    }

    // ==================================================================
    // 构造真实文件
    // ==================================================================

    /** 构造一个真实 PDF：每个元素是一页的文本行。 */
    private byte[] buildPdf(List<String>... pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(50, 700);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -18);
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 构造一个真实 DOCX，可含段落与表格。 */
    private byte[] buildDocx(List<String> paragraphs, List<List<String>> tableRows) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (String text : paragraphs) {
                XWPFParagraph p = document.createParagraph();
                p.createRun().setText(text);
            }

            if (tableRows != null && !tableRows.isEmpty()) {
                XWPFTable table = document.createTable(tableRows.size(), tableRows.get(0).size());
                for (int r = 0; r < tableRows.size(); r++) {
                    List<String> row = tableRows.get(r);
                    for (int c = 0; c < row.size(); c++) {
                        table.getRow(r).getCell(c).setText(row.get(c));
                    }
                }
            }

            document.write(out);
            return out.toByteArray();
        }
    }
}
