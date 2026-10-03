package com.demo.contract.parse.text;

import com.demo.contract.parse.ContractException;
import com.demo.contract.parse.ParseErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 从 PDF / DOCX 提取纯文本。
 *
 * <p>本类只负责"把文件变成字符串"，<b>不做归一化</b>——归一化在
 * {@link TextNormalizer}，它需要维护坐标映射，是独立且可单测的关注点。
 *
 * <p>错误处理的原则：<b>每一种可区分的失败都要有独立错误码</b>，
 * 因为用户能采取的行动不同——加密的要解密后重传，扫描件要换文本版，
 * 损坏的只能换文件。合并成一个"解析失败"会让用户完全不知道该做什么。
 */
@Component
public class ContractTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ContractTextExtractor.class);

    /**
     * 提取文本。
     *
     * @param in       文件流，由调用方负责关闭
     * @param filename 原名，仅用于按扩展名选择提取器
     * @throws ContractException 失败时携带明确错误码
     */
    public ExtractionResult extract(InputStream in, String filename) {
        String ext = extensionOf(filename);
        return switch (ext) {
            case "pdf" -> extractPdf(in);
            case "docx" -> extractDocx(in);
            default -> throw new ContractException(ParseErrorCode.MIME_MISMATCH,
                    "不支持的文档格式: " + ext);
        };
    }

    // ==================================================================
    // PDF
    // ==================================================================

    private ExtractionResult extractPdf(InputStream in) {
        PDDocument document;
        try {
            // 用 Loader.loadPDF(InputStream) 而不是已废弃的 PDDocument.load
            document = Loader.loadPDF(in.readAllBytes());
        } catch (InvalidPasswordException e) {
            // 加密 PDF：用户能自己解决（解密后重传），因此必须有独立错误码
            throw new ContractException(ParseErrorCode.PDF_ENCRYPTED,
                    "文件已加密，请解密后重新上传", e);
        } catch (IOException e) {
            throw new ContractException(ParseErrorCode.PARSE_FAILED,
                    "PDF 文件损坏或无法读取", e);
        }

        try (document) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                // 0 页的 PDF 属于异常文件，不是"没有文本"
                throw new ContractException(ParseErrorCode.PARSE_FAILED, "PDF 没有任何页面");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // 按坐标排序，避免双栏排版读出乱序

            // 逐页提取并用换行拼接：保留分页边界，供归一化阶段识别页眉页脚
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(pageText);
                }
            }

            ExtractionResult result = new ExtractionResult(sb.toString(), pageCount);
            logExtraction("PDF", pageCount, result);
            return result;
        } catch (IOException e) {
            throw new ContractException(ParseErrorCode.PARSE_FAILED, "PDF 文本提取失败", e);
        }
    }

    // ==================================================================
    // DOCX
    // ==================================================================

    /**
     * 提取 DOCX 文本。
     *
     * <p>两个容易漏的点：
     * <ol>
     *   <li><b>表格内容</b>：合同里的金额、期限常在表格中。只读段落会丢掉关键信息，
     *       而且是"静默丢失"——提取"成功"了，但金额不见了。</li>
     *   <li><b>段落与换行</b>：一个 {@code w:p} 是一行。用 {@code paragraph.getText()} 拿全文，
     *       再把表格按行追加。</li>
     * </ol>
     */
    private ExtractionResult extractDocx(InputStream in) {
        try (XWPFDocument document = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }

            // 表格：逐行拼接，单元格之间用制表符分隔
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder rowText = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (rowText.length() > 0) {
                            rowText.append('\t');
                        }
                        rowText.append(cell.getText() == null ? "" : cell.getText().trim());
                    }
                    String line = rowText.toString();
                    if (!line.isBlank()) {
                        sb.append(line).append('\n');
                    }
                }
            }

            // DOCX 没有固定分页概念，按 1 页处理。
            // 后果是不会触发页眉页脚清理——刻意的保守选择：
            // 宁可留下页眉，也不要因为猜错而删掉正文。
            ExtractionResult result = new ExtractionResult(sb.toString(), 1);
            logExtraction("DOCX", 1, result);
            return result;
        } catch (IOException e) {
            throw new ContractException(ParseErrorCode.PARSE_FAILED,
                    "DOCX 文件损坏或无法读取", e);
        } catch (RuntimeException e) {
            // POI 对格式异常的文件有时抛非受检异常（如 XmlException 包装后的运行时异常）
            throw new ContractException(ParseErrorCode.PARSE_FAILED,
                    "DOCX 文件格式异常", e);
        }
    }

    private void logExtraction(String kind, int pageCount, ExtractionResult result) {
        if (result.isEmpty()) {
            log.warn("{} 提取结果为空，疑似扫描件或纯图片文档：页数={}", kind, pageCount);
        } else {
            log.info("{} 提取完成：页数={} 字符数={}", kind, pageCount, result.rawLength());
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
