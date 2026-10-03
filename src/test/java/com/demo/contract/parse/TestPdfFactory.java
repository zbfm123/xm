package com.demo.contract.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 测试用真实 PDF 构造器。
 *
 * <p>为什么构造真实文件而不是喂假字节：提取层的失败模式（编码、字体、加密、页数）
 * 只有真实文件才会触发。用假的字节流测出来的"通过"没有意义。
 *
 * <p>⚠️ 内容全部为虚构。
 */
@Component
public class TestPdfFactory {

    /** 构造单页 PDF，每行一段文本。 */
    public byte[] build(List<String> lines) throws IOException {
        return buildPages(lines);
    }

    /**
     * 构造多页 PDF，每个元素是一页的文本行。
     *
     * <p>刻意不重载 {@code build}：与 {@link #build(List)} 构成泛型重载后，
     * 调用 {@code build(List.of(List.of("a")))} 会产生歧义编译错误。
     * <b>泛型重载很容易在调用点变成陷阱，用不同方法名更安全。</b>
     */
    @SafeVarargs
    public final byte[] buildPages(List<String>... pages) throws IOException {
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
            return save(document);
        }
    }

    /** 构造只有空白页的 PDF，等价于扫描件（能打开但没有文本）。 */
    public byte[] buildBlankPage() throws IOException {
        return build(List.of(""));
    }

    /**
     * 构造加密 PDF。
     *
     * <p>用 PDFBox 的标准保护策略加密。调用方需要能识别
     * {@code InvalidPasswordException} 并映射为 {@code PDF_ENCRYPTED}。
     */
    public byte[] buildEncrypted(String body) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(body);
                cs.endText();
            }

            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner-password", "user-password", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);

            return save(document);
        }
    }

    private byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
