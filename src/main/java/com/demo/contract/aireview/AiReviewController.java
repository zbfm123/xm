package com.demo.contract.aireview;

import com.demo.contract.aireview.domain.AiFindingRow;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.extract.domain.ContractElementRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 要素抽取与 AI 风险审查接口。
 *
 * <p>全部需要登录，租户范围由 Service 从上下文取。
 */
@RestController
@RequestMapping("/api/contracts/{id}")
public class AiReviewController {

    private final ElementExtractionService extractionService;
    private final AiReviewService aiReviewService;

    public AiReviewController(ElementExtractionService extractionService,
                              AiReviewService aiReviewService) {
        this.extractionService = extractionService;
        this.aiReviewService = aiReviewService;
    }

    /**
     * 要素抽取。
     *
     * <p>返回里刻意把 {@code mismatch}（引文无法定位的字段数）单独列出：
     * 它是本模块最重要的健康指标，偏高说明模型在改写引文。
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(@PathVariable("id") Long id) {
        var s = extractionService.extract(id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractId", s.contractId());
        body.put("total", s.total());
        body.put("aligned", s.aligned());
        body.put("mismatch", s.mismatch());
        body.put("alignmentRate", Math.round(s.alignmentRate() * 1000) / 1000.0);
        body.put("removedPrevious", s.removedPrevious());
        body.put("provider", s.provider());
        body.put("message", s.mismatch() > 0
                ? "有 " + s.mismatch() + " 个字段的引文无法在原文定位，已标为 UNKNOWN 并转人工"
                : "全部字段的引文均已在原文定位");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/elements")
    public ResponseEntity<List<Map<String, Object>>> elements(@PathVariable("id") Long id) {
        return ResponseEntity.ok(extractionService.elements(id).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fieldKey", r.getFieldKey());
            m.put("elementValue", r.getElementValue());
            m.put("quote", r.getQuote());
            m.put("charStart", r.getCharStart());
            m.put("charEnd", r.getCharEnd());
            m.put("confidence", r.getConfidence());
            m.put("matchLevel", r.getMatchLevel());
            m.put("status", r.getStatus());
            m.put("statusReason", r.getStatusReason());
            m.put("source", r.getSource());
            return m;
        }).toList());
    }

    /**
     * AI 风险审查。
     *
     * <p>{@code reportable} = 证据已定位、可进入报告正文的条数；
     * {@code mismatch} = 引文无法定位、**只作为人工待办**的条数。
     */
    @PostMapping("/ai-review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable("id") Long id) {
        var s = aiReviewService.review(id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractId", s.contractId());
        body.put("total", s.total());
        body.put("pending", s.pending());
        body.put("lowConfidence", s.lowConfidence());
        body.put("mismatch", s.mismatch());
        body.put("reportable", s.reportable());
        body.put("alignmentRate", Math.round(s.alignmentRate() * 1000) / 1000.0);
        body.put("removedPrevious", s.removedPrevious());
        body.put("provider", s.provider());
        body.put("modelVersion", s.modelVersion());
        body.put("promptVersion", s.promptVersion());
        body.put("message", buildMessage(s));
        return ResponseEntity.ok(body);
    }

    private String buildMessage(AiReviewService.ReviewSummary s) {
        if (s.total() == 0) {
            return "未发现风险条款（注意：这不等于合同没有风险）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(s.total()).append(" 条候选结论，全部为待人工采信状态。");
        if (s.mismatch() > 0) {
            sb.append("其中 ").append(s.mismatch())
              .append(" 条引文无法在原文定位，已转人工且不进报告正文。");
        }
        if (s.lowConfidence() > 0) {
            sb.append("另有 ").append(s.lowConfidence())
              .append(" 条置信度偏低，需复核。");
        }
        return sb.toString();
    }

    /**
     * 查询 AI 候选结论。
     *
     * @param reportableOnly 传 true 只返回证据已定位、可进报告正文的条目
     */
    @GetMapping("/ai-findings")
    public ResponseEntity<List<Map<String, Object>>> findings(
            @PathVariable("id") Long id,
            @RequestParam(value = "reportableOnly", defaultValue = "false") boolean reportableOnly) {

        List<AiFindingRow> rows = reportableOnly
                ? aiReviewService.reportableFindings(id)
                : aiReviewService.findings(id);

        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("riskType", r.getRiskType());
            m.put("quote", r.getQuote());
            m.put("charStart", r.getCharStart());
            m.put("charEnd", r.getCharEnd());
            m.put("confidence", r.getConfidence());
            m.put("matchLevel", r.getMatchLevel());
            // 状态原样返回：前端必须能区分"待采信"与"证据不匹配"
            m.put("status", r.getStatus());
            m.put("statusReason", r.getStatusReason());
            m.put("modelVersion", r.getModelVersion());
            m.put("promptVersion", r.getPromptVersion());
            m.put("reportable", r.getCharStart() != null);
            return m;
        }).toList());
    }
}
