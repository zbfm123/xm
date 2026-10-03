package com.demo.contract.web;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 生成虚构的示例合同 PDF，供调试台与手工验证使用。
 *
 * <p><b>只在 dev / test 环境注册</b>（{@code @Profile}）：
 * 这类辅助端点没有任何理由出现在生产环境里。
 * 用 profile 控制比"部署时记得关掉"可靠得多——后者总会忘。
 *
 * <p><b>为什么放在应用里而不是写成外部小工具</b>：
 * 外部工具需要单独维护一份 PDFBox classpath（版本、传递依赖都得手工对齐），
 * 我为此连续踩了两次（缺 {@code pdfbox-io}、TTC 字体不能直接 load）。
 * 放在应用里则复用已有的依赖与字体解析逻辑，零额外维护成本。
 *
 * <p>两种模板覆盖了规则引擎的两个关键场景：
 * <ul>
 *   <li>{@code well-formed} —— 要素齐全，四条规则都能给出确定结论</li>
 *   <li>{@code sparse} —— 只有正文没有要素，用于验证"无法判定"而不是"通过"</li>
 * </ul>
 *
 * <p>⚠️ 内容全部为虚构，不含任何真实合同信息。
 */
@RestController
@RequestMapping("/api/debug")
@org.springframework.context.annotation.Profile({"dev", "test", "default"})
public class SampleContractController {

    private static final Logger log = LoggerFactory.getLogger(SampleContractController.class);

    /**
     * 候选 CJK 字体。
     *
     * <p>⚠️ 必须用 <b>.ttf</b> 而不是 .ttc：{@code msyh.ttc} 是字体集合，
     * {@code PDType0Font.load} 会抛 {@code IOException: 'head' table is mandatory}。
     * 这个坑我在外部小工具里踩过一次。
     */
    private static final String[] CJK_TTF_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",   // 黑体（单体 TTF）
            "C:/Windows/Fonts/simsunb.ttf",
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/System/Library/Fonts/Supplemental/Songti.ttc"
    };

    @GetMapping(value = "/sample-contract.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> sampleContract(
            @RequestParam(value = "template", defaultValue = "well-formed") String template)
            throws IOException {

        List<String> lines = switch (template) {
            case "sparse" -> List.of(
                    "说明文件（虚构样例）",
                    "本文只是一段普通说明文字，没有写甲乙方，也没有写金额和日期。",
                    "付款方式见附件，争议解决另行约定，违约责任另议。");
            default -> List.of(
                    "采购合同（虚构样例）",
                    "甲方：北京某某科技有限公司",
                    "乙方：上海某某贸易有限公司",
                    "签订日期：2026-01-01",
                    "生效日期：2026-01-05",
                    "到期日期：2027-01-04",
                    "合同金额：128000.00元",
                    "大写：壹拾贰万捌仟元整",
                    "付款方式：分两期支付。",
                    "违约责任：按日万分之五。",
                    "争议解决：提交北京仲裁委员会。");
        };

        byte[] pdf = render(lines);

        String filename = "sample-" + template + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private byte[] render(List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDFont font = resolveFont(document);

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(font, 11);
                cs.newLineAtOffset(40, 780);
                cs.setLeading(15);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDFont resolveFont(PDDocument document) throws IOException {
        for (String path : CJK_TTF_CANDIDATES) {
            File f = new File(path);
            if (!f.isFile()) {
                continue;
            }
            try {
                return PDType0Font.load(document, f);
            } catch (IOException | IllegalArgumentException e) {
                log.debug("字体不可用，尝试下一个: {} ({})", path, e.toString());
            }
        }
        // 没有 CJK 字体时退回 Helvetica：只能写 ASCII，
        // 调用方会看到乱码或异常，而不是静默产出"看起来正常但内容为空"的 PDF
        log.warn("未找到可用的 CJK 字体，示例合同将使用 Helvetica，中文可能无法渲染");
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /** 供排错：列出实际可用的字体路径。 */
    @GetMapping(value = "/fonts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> fonts() {
        StringBuilder sb = new StringBuilder("{\"candidates\":[");
        boolean first = true;
        for (String path : CJK_TTF_CANDIDATES) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"path\":\"").append(path)
              .append("\",\"exists\":").append(new File(path).isFile()).append('}');
        }
        sb.append("]}");
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(sb.toString());
    }
}
