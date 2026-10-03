package com.demo.contract.web;

import com.demo.contract.extract.evidence.AlignmentResult;
import com.demo.contract.extract.evidence.EvidenceAligner;
import com.demo.contract.parse.ContractParsingService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 证据对齐调试端点。
 *
 * <p><b>只在 dev / test 环境注册</b>：这是排查与演示工具，不是业务接口。
 *
 * <p>存在的价值：证据对齐是本项目最核心的算法，但它藏在规则与 AI 之后，
 * 光看最终结论无法判断"到底是匹配成功了还是降级了"。
 * 这个端点把对齐过程<b>直接暴露出来</b>——给一句引文，看它落在原文哪里、用了哪一级匹配、
 * 相似度多少、为什么失败。
 *
 * <p>典型用法：
 * <pre>
 * # 完全一致的引文 → EXACT
 * GET /api/debug/align/1?quote=付款方式
 *
 * # 改一个字的引文 → FUZZY，相似度 &lt; 1
 * GET /api/debug/align/1?quote=付款方式为分两期支付
 *
 * # 原文里不存在的内容 → 未命中，且不返回任何位置
 * GET /api/debug/align/1?quote=乙方承担一切损失
 * </pre>
 */
@RestController
@RequestMapping("/api/debug/align")
@Profile({"dev", "test", "default"})
public class DebugAlignController {

    private final ContractParsingService parsingService;
    private final EvidenceAligner aligner;

    public DebugAlignController(ContractParsingService parsingService, EvidenceAligner aligner) {
        this.parsingService = parsingService;
        this.aligner = aligner;
    }

    /**
     * 对指定合同执行一次证据对齐。
     *
     * @param context 可选的上下文，用于在引文多处出现时消歧
     */
    @GetMapping(value = "/{contractId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> align(
            @PathVariable("contractId") Long contractId,
            @RequestParam("quote") String quote,
            @RequestParam(value = "context", required = false) String context) {

        var map = parsingService.requireAlignmentMap(contractId);
        AlignmentResult result = (context == null || context.isBlank())
                ? aligner.align(quote, map)
                : aligner.alignWithContext(context, quote, map);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matched", result.matched());
        body.put("level", result.level().name());
        body.put("levelWeight", result.levelWeight());
        body.put("similarity", result.similarity());
        body.put("ambiguous", result.ambiguous());
        body.put("failure", result.failure() == null ? null : result.failure().name());
        body.put("quote", quote);
        body.put("matchedText", result.matchedText());

        if (result.matched()) {
            body.put("originalStart", result.start());
            body.put("originalEnd", result.end());
            // 回取的原文片段：人工核对时看的就是这一段
            body.put("originalSnippet", map.originalText().substring(result.start(), result.end()));
            body.put("explanation", explain(result));
        } else {
            body.put("originalStart", null);
            body.put("originalEnd", null);
            body.put("explanation", explainMiss(result));
        }

        return ResponseEntity.ok(body);
    }

    /** 展示映射与文本规模，便于确认归一化是否正常工作。 */
    @GetMapping(value = "/{contractId}/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> info(@PathVariable("contractId") Long contractId) {
        var map = parsingService.requireAlignmentMap(contractId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalLength", map.originalText().length());
        body.put("normalizedLength", map.normalizedText().length());
        body.put("charsRemoved", map.originalText().length() - map.normalizedText().length());
        body.put("normalizedContainsNewline", map.normalizedText().contains("\n"));
        body.put("mappingConsistent",
                map.offsetsToOriginal().length == map.normalizedText().length());
        return ResponseEntity.ok(body);
    }

    private String explain(AlignmentResult r) {
        return switch (r.level()) {
            case EXACT -> "引文与原文逐字相同，可信度最高";
            case NORMALIZED -> "引文与原文存在全角/半角或空白差异，"
                    + "已通过归一化匹配并把区间回映射到原文坐标";
            case FUZZY -> String.format(
                    "引文与原文有约 %.1f%% 的差异，属模糊命中——"
                            + "可用，但置信度会按级别下调，建议人工核对原文片段",
                    (1 - r.similarity()) * 100);
            case NONE -> "未命中";
        };
    }

    private String explainMiss(AlignmentResult r) {
        return switch (r.failure()) {
            case NOT_FOUND -> "引文在原文中找不到。可能是模型幻觉出了原文没有的内容，"
                    + "或引用被大幅改写。**系统不会给出近似位置**——"
                    + "给一个'差不多'的区间会让人工跳到无关的地方。";
            case AMBIGUOUS -> "同一引文在原文中出现多次，机器无法判断指的是哪一处。"
                    + "可传入 context 参数（引文的前后文）来消歧。";
            case TOO_SHORT -> "引文过短，命中它没有证据价值（例如'甲方'能命中全文）。";
            case INVALID_INPUT -> "输入非法：引文为空，或原文为空。";
        };
    }
}
