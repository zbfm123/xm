package com.demo.contract.aireview.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock 模型客户端。
 *
 * <p>在 {@code app.ai.enabled=false} 时启用。
 * 用 {@code @ConditionalOnProperty} 而不是 {@code @Primary} 让两个实现互斥：
 * <b>两个 {@code AiClient} bean 同时存在时，注入会变得依赖 bean 名称与顺序，
 * 那种"能跑但说不清"的状态在排查时非常费劲。</b>
 *
 * <p>三个用途：
 * <ol>
 *   <li><b>零额度消耗的开发与测试</b>——接口额度只有 15 元，
 *       真正的风险是调试时的循环调用</li>
 *   <li><b>断网演示</b>——面试现场网络不可控，Mock 让流程照样跑通</li>
 *   <li><b>降级展示</b>——{@code AI_ENABLED=false} 就是不变式 I-04 的开关</li>
 * </ol>
 *
 * <p><b>关键设计：桩不返回写死的 JSON，而是从输入文本里提取真实片段。</b>
 *
 * <p>这一点很重要。如果桩返回固定内容，那么证据对齐会（正确地）
 * 把它判为"原文中不存在"从而降级——测试与演示看到的全是降级状态，
 * 完全测不到正向链路。<b>桩必须产出能被真实对齐算法命中的引文。</b>
 *
 * <p>做法：从输入里找出含风险关键词的句子，把<b>整句原文</b>作为 quote 返回。
 * 这样对齐必然是 EXACT，而风险判断逻辑本身则由 Mock 的固定规则给出。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
public class MockAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(MockAiClient.class);

    /**
     * 风险关键词表。
     *
     * <p>桩用它与真实文本做匹配。这不是"AI 判断"，只是一条可复现的规则——
     * 它的价值在于让下游拿到<b>结构正确、且引文真实存在</b>的数据。
     */
    private static final Map<String, String[]> RISK_KEYWORDS = new LinkedHashMap<>() {{
        put("UNLIMITED_LIABILITY", new String[]{"一切损失", "全部损失", "承担全部责任", "无限"});
        put("UNILATERAL_TERMINATION", new String[]{"单方解除", "有权解除", "无需理由解除"});
        put("AUTO_RENEWAL", new String[]{"自动续约", "自动续期", "自动延长"});
        put("VAGUE_PAYMENT", new String[]{"另行约定", "见附件", "另行商定"});
        put("UNCAPPED_PENALTY", new String[]{"违约金", "滞纳金"});
        put("JURISDICTION_UNCLEAR", new String[]{"另行协商解决", "友好协商"});
    }};

    /**
     * 要素抽取的桩关键词。
     *
     * <p>同样遵循"从原文取片段"的原则：quote 与 value 都来自输入文本。
     */
    private static final Map<String, String[]> ELEMENT_PATTERNS = new LinkedHashMap<>() {{
        put("partyA", new String[]{"甲方：", "甲方:"});
        put("partyB", new String[]{"乙方：", "乙方:"});
        put("paymentTerm", new String[]{"付款方式：", "付款方式:"});
        put("disputeResolution", new String[]{"争议解决：", "争议解决:"});
    }};

    private final ObjectMapper objectMapper;

    public MockAiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        log.info("AI 通道使用 Mock 桩（不消耗额度）。"
                + "切换真实调用：设置 AI_ENABLED=true 与 DEEPSEEK_API_KEY");
    }

    @Override
    public String complete(String systemPrompt, String userContent) {
        // 桩是"永远成功"的。异常分支由测试直接构造 AiCallException 来覆盖，
        // 不需要让桩本身变得可失败——那会让正常路径的测试也必须处理失败。
        try {
            if (systemPrompt.contains("风险条款")) {
                return objectMapper.writeValueAsString(riskResponse(userContent));
            }
            if (systemPrompt.contains("要素抽取")) {
                return objectMapper.writeValueAsString(elementResponse(userContent));
            }
            // 默认返回一个结构合法但内容为空的结果，而不是抛异常
            return objectMapper.writeValueAsString(Map.of("findings", List.of()));
        } catch (Exception e) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "Mock 桩序列化失败", e);
        }
    }

    /**
     * 构造风险审查的桩响应。
     *
     * <p>每个命中都带上<b>从原文截取的整句</b>作为 quote，因此真实的对齐算法
     * 能把它判为 EXACT 命中——这正是我们要测的正向链路。
     */
    private Map<String, Object> riskResponse(String text) {
        List<Map<String, Object>> findings = new ArrayList<>();

        for (String sentence : splitSentences(text)) {
            for (Map.Entry<String, String[]> entry : RISK_KEYWORDS.entrySet()) {
                for (String keyword : entry.getValue()) {
                    if (sentence.contains(keyword)) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("riskType", entry.getKey());
                        // quote 直接用原文整句：保证真实对齐能命中
                        f.put("quote", sentence);
                        // 桩给出的自评置信度：关键词越长越"有信心"
                        f.put("confidence", Math.min(0.95, 0.55 + keyword.length() * 0.05));
                        f.put("reason", "命中关键词「" + keyword + "」（Mock 桩规则）");
                        findings.add(f);
                        break;   // 一句话对一个风险类型只报一次
                    }
                }
            }
        }

        // 刻意留一个"幻觉"样本，让上游的降级路径在演示时也能被看到。
        // 只有文本里确实出现了特定词时才加，避免污染正常用例。
        if (text.contains("演示幻觉")) {
            Map<String, Object> hallucination = new LinkedHashMap<>();
            hallucination.put("riskType", "UNKNOWN_RISK");
            hallucination.put("quote", "这一段文字在原文中根本不存在，用于演示证据对齐失败");
            hallucination.put("confidence", 0.99);
            hallucination.put("reason", "Mock 桩故意构造的幻觉，用于验证证据对齐会把它拦下来");
            findings.add(hallucination);
        }

        return Map.of("findings", findings);
    }

    /** 构造要素抽取的桩响应。 */
    private Map<String, Object> elementResponse(String text) {
        Map<String, Object> elements = new LinkedHashMap<>();

        for (Map.Entry<String, String[]> entry : ELEMENT_PATTERNS.entrySet()) {
            for (String marker : entry.getValue()) {
                int idx = text.indexOf(marker);
                if (idx >= 0) {
                    String value = readValueAfter(text, idx + marker.length());
                    if (!value.isEmpty()) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("value", value);
                        e.put("quote", marker + value);
                        e.put("confidence", 0.91);
                        elements.put(entry.getKey(), e);
                        break;
                    }
                }
            }
        }

        return Map.of("elements", elements);
    }

    /** 取标记之后到行尾/分隔符之前的一段。 */
    private String readValueAfter(String text, int from) {
        int end = from;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '\n' || c == ';' || c == '；' || c == '。' || c == ' ') {
                break;
            }
            end++;
        }
        return text.substring(from, end).trim();
    }

    /**
     * 切句：按中文句号、分号与换行切分。
     *
     * <p>真实实现应当按条款结构切分；桩用简单切分即可，
     * 因为它的目标只是"产出真实存在的引文"。
     */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            current.append(c);
            if (c == '。' || c == '；' || c == ';' || c == '\n') {
                String s = current.toString().replace("\n", "").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    @Override
    public boolean isAvailable() {
        // 桩永远可用：它不依赖网络与额度，这正是它作为降级手段的价值
        return true;
    }

    @Override
    public String providerName() {
        return "mock(零消耗)";
    }
}
