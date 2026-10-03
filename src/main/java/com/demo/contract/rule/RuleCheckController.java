package com.demo.contract.rule;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则校验接口。
 *
 * <p>全部需要登录（不在白名单中），租户范围由 Service 从上下文取。
 */
@RestController
@RequestMapping("/api/contracts/{id}")
public class RuleCheckController {

    private final RuleCheckService ruleCheckService;

    public RuleCheckController(RuleCheckService ruleCheckService) {
        this.ruleCheckService = ruleCheckService;
    }

    /**
     * 执行规则校验。
     *
     * <p>返回三类计数与要素抽取摘要。**刻意把"无法判定"单独列出**，
     * 因为它是本系统最重要的健康指标——偏高意味着抽取质量差或规则依赖的字段没产出。
     */
    @PostMapping("/rule-check")
    public ResponseEntity<Map<String, Object>> check(@PathVariable("id") Long id) {
        RuleCheckService.CheckSummary s = ruleCheckService.check(id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractId", s.contractId());
        body.put("total", s.total());
        body.put("hits", s.hits());
        body.put("passes", s.passes());
        body.put("undetermined", s.undetermined());
        body.put("ruleErrors", s.ruleErrors());
        body.put("needsHumanAttention", s.needsHumanAttention());
        body.put("extractedElements", s.extractedElements());
        body.put("removedPreviousFindings", s.removedPreviousFindings());
        body.put("message", s.hasRuleErrors()
                ? "部分规则执行失败，对应结论已标记，请查看明细"
                : (s.undetermined() > 0
                        ? "存在无法判定的结论（信息不足），需人工确认"
                        : "全部规则均已给出确定结论"));
        return ResponseEntity.ok(body);
    }

    /**
     * 查询结论列表。
     *
     * @param onlyHits 传 true 只看命中项；默认返回全部（含通过与无法判定）
     */
    @GetMapping("/findings")
    public ResponseEntity<List<Map<String, Object>>> findings(
            @PathVariable("id") Long id,
            @org.springframework.web.bind.annotation.RequestParam(value = "onlyHits", defaultValue = "false")
            boolean onlyHits) {

        var list = onlyHits ? ruleCheckService.hits(id) : ruleCheckService.findings(id);

        List<Map<String, Object>> body = list.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleCode", f.getRuleCode());
            m.put("ruleName", f.getRuleName());
            m.put("severity", f.getSeverity());
            // 三态原样返回，前端必须能区分"命中/通过/无法判定"
            m.put("result", f.getResult());
            m.put("evidence", f.getEvidence());
            m.put("charStart", f.getCharStart());
            m.put("charEnd", f.getCharEnd());
            m.put("detail", f.getDetail());
            m.put("errorMessage", f.getErrorMessage());
            m.put("checkedAt", f.getCheckedAt() == null ? null : f.getCheckedAt().toString());
            return m;
        }).toList();

        return ResponseEntity.ok(body);
    }
}
