package com.demo.contract.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 测试用文件工厂。
 *
 * <p>刻意生成**最小的合法文件头**而不是完整的 PDF/DOCX：
 * 上传校验只看魔数，本阶段（T-006）不解析内容，因此不需要真实的复杂文件。
 * 真实解析在 T-009 处理，届时这里会补上可解析的样例。
 *
 * <p>⚠️ 所有内容均为虚构，不得放入真实合同。
 */
public final class TestFiles {

    private TestFiles() {
    }

    /**
     * 生成一个以 {@code %PDF} 开头的最小 PDF 字节流。
     *
     * <p>⚠️ 这里用字符串拼接而不是 {@code formatted()}：
     * PDF 头部的 {@code %PDF} 里的 {@code %P} 会被当成格式说明符，
     * 导致 {@code UnknownFormatConversionException}。
     * <b>把含 {@code %} 的模板交给格式化方法是个常见陷阱。</b>
     */
    public static byte[] minimalPdf(String marker) {
        String body = "%PDF-1.4\n"
                + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj\n"
                + "% marker: " + marker + "\n"
                + "% 本文件为测试用虚构合同样例，不含任何真实信息。\n"
                + "trailer << /Root 1 0 R >>\n"
                + "%%EOF\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** 生成一个以 {@code PK} 开头的最小 DOCX（ZIP 容器）。 */
    public static byte[] minimalDocx(String marker) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                    </Types>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>虚构测试合同 %s</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.formatted(marker)).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 生成扩展名是 .pdf 但内容不是 PDF 的文件，用于魔数校验测试。 */
    public static byte[] fakePdfWithWrongContent() {
        return "这其实是一个文本文件，只是名字叫 .pdf".getBytes(StandardCharsets.UTF_8);
    }
}
