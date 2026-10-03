package com.demo.contract.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 测试用真实 PDF 构造器。
 *
 * <p>为什么构造真实文件而不是喂假字节：提取层的失败模式（编码、字体、加密、页数）
 * 只有真实文件才会触发。用假的字节流测出来的"通过"没有意义。
 *
 * <p><b>关于中文字体</b>：PDFBox 内置的 Helvetica 只有 WinAnsi 编码，
 * 写入中文会抛 {@code IllegalArgumentException: U+7532 ('.notdef') is not available}。
 * 而本项目的测试数据是中文合同，因此必须嵌入一个 CJK 字体。
 * 这里从 Windows 系统字体目录找一个可用字体；找不到时退回 Helvetica，
 * 并<b>由调用方自行决定是否只测 ASCII</b>——不静默降级成乱码。
 *
 * <p>⚠️ 内容全部为虚构。
 */
@Component
public class TestPdfFactory {

    /** 候选系统字体，按优先级排列。取第一个存在的。 */
    private static final String[] CJK_FONT_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc",     // 微软雅黑
            "C:/Windows/Fonts/simsun.ttc",   // 宋体
            "C:/Windows/Fonts/simhei.ttf",   // 黑体
            "C:/Windows/Fonts/msyh.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/System/Library/Fonts/PingFang.ttc"
    };

    /** 构造单页 PDF，每行一段文本。 */
    public byte[] build(List<String> lines) throws IOException {
        return buildPages(lines);
    }

    /**
     * 构造多页 PDF，每个元素是一页的文本行。
     *
     * <p>刻意不重载 {@code build}：与 {@link #build(List)} 构成泛型重载后，
     * 调用 {@code build(List.of(List.of("a")))} 会产生歧义编译错误。
     */
    @SafeVarargs
    public final byte[] buildPages(List<String>... pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont font = resolveFont(document);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(40, 780);
                    cs.setLeading(14);
                    for (String line : lines) {
                        cs.showText(sanitize(line));
                        cs.newLine();
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
     * <p>调用方需要能识别 {@code InvalidPasswordException} 并映射为 {@code PDF_ENCRYPTED}。
     */
    public byte[] buildEncrypted(String body) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDFont font = resolveFont(document);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(40, 780);
                cs.showText(sanitize(body));
                cs.endText();
            }

            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner-password", "user-password",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);

            return save(document);
        }
    }

    /**
     * 选择可用字体。
     *
     * <p>优先嵌入 CJK 字体；都没有时退回 Helvetica（只能写 ASCII）。
     */
    private PDFont resolveFont(PDDocument document) throws IOException {
        for (String path : CJK_FONT_CANDIDATES) {
            File f = new File(path);
            if (f.isFile()) {
                try {
                    return PDType0Font.load(document, f);
                } catch (IOException | IllegalArgumentException e) {
                    // 该字体不可用就试下一个，不静默放弃整个候选列表
                    continue;
                }
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /**
     * 清理不能写入 PDF 的字符。
     *
     * <p>PDF 文本层对控制字符不友好，直接把原文写进去可能抛异常。
     * 这里只做最小清理，不改动正常字符——测试数据应当尽量保持原样。
     */
    private String sanitize(String line) {
        if (line == null) {
            return "";
        }
        return line.replace("\t", "    ").replace("\r", "");
    }

    private byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
