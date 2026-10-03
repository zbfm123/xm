package com.demo.contract.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 测试用真实 DOCX 构造器。内容全部为虚构。
 */
@Component
public class TestDocxFactory {

    /** 只含段落的 DOCX。 */
    public byte[] build(List<String> paragraphs) throws IOException {
        return build(paragraphs, null);
    }

    /**
     * 含段落与表格的 DOCX。
     *
     * @param tableRows 第一行作为表头；为 null 时不加表格
     */
    public byte[] build(List<String> paragraphs, List<List<String>> tableRows) throws IOException {
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

    /** 只有空白段落的 DOCX。 */
    public byte[] buildBlank() throws IOException {
        return build(List.of("   ", ""));
    }
}
