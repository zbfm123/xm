package com.demo.contract.aireview.client;

import com.demo.contract.extract.evidence.AlignmentResult;
import com.demo.contract.extract.evidence.EvidenceAligner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 桩的行为测试。
 *
 * <p><b>本测试要证明的关键命题：桩产出的引文必须能被真实的证据对齐算法命中。</b>
 *
 * <p>如果桩返回写死的内容（例如固定的"乙方承担一切损失"），
 * 那么对齐算法会（正确地）把它判为"原文中不存在"从而降级——
 * 演示与测试看到的全是降级状态，<b>正向链路完全测不到</b>。
 *
 * <p>所以桩必须从输入文本里截取真实片段。这个测试把这条要求固化下来。
 */
class MockAiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockAiClient client = new MockAiClient(objectMapper);
    private final EvidenceAligner aligner = new EvidenceAligner();

    /** 一份含多种风险的虚构合同正文（归一化后的形态）。 */
    private static final String CONTRACT_TEXT = String.join(" ",
            "采购合同（虚构样例）",
            "甲方：北京某某科技有限公司",
            "乙方：上海某某贸易有限公司",
            "付款方式：另行约定。",
            "因本合同产生的一切损失均由乙方承担全部责任。",
            "甲方有权解除本合同且无需理由解除。",
            "本合同期满后自动续约一年。",
            "争议解决：友好协商。");

    @Test
    @DisplayName("风险审查：桩从原文截取引文，而非返回写死内容")
    void riskQuotesMustComeFromInputText() throws Exception {
        String raw = client.complete("你是合同风险条款识别助手，请输出 JSON", CONTRACT_TEXT);
        JsonNode findings = objectMapper.readTree(raw).path("findings");

        assertThat(findings.isArray()).isTrue();
        assertThat(findings.size())
                .withFailMessage("桩未识别出任何风险，桩规则可能失效")
                .isGreaterThan(0);

        for (JsonNode f : findings) {
            String quote = f.path("quote").asText();
            assertThat(quote).isNotBlank();
            // 核心断言：引文必须真的出现在输入文本里
            assertThat(CONTRACT_TEXT)
                    .withFailMessage("桩产出的引文不在原文中，对齐算法会把它判为幻觉：%s", quote)
                    .contains(quote);
        }
    }

    @Test
    @DisplayName("桩产出的每一条引文都能被真实对齐算法命中（至少 NORMALIZED 级别）")
    void everyMockQuoteMustAlignSuccessfully() throws Exception {
        String raw = client.complete("你是合同风险条款识别助手，请输出 JSON", CONTRACT_TEXT);
        JsonNode findings = objectMapper.readTree(raw).path("findings");

        var map = identityMap(CONTRACT_TEXT);
        List<String> failures = new ArrayList<>();

        for (JsonNode f : findings) {
            String quote = f.path("quote").asText();
            AlignmentResult result = aligner.align(quote, map);
            if (!result.matched()) {
                failures.add(quote + " -> " + result.failure());
            }
        }

        assertThat(failures)
                .withFailMessage("""
                        桩产出的引文无法被对齐算法命中，说明桩与真实链路不匹配。
                        演示时会看到所有结论都被降级，正向链路测不到：
                        %s
                        """, String.join("\n", failures))
                .isEmpty();
    }

    @Test
    @DisplayName("风险类型来自固定枚举，且带置信度与理由")
    void findingsShouldCarryRequiredFields() throws Exception {
        String raw = client.complete("你是合同风险条款识别助手，请输出 JSON", CONTRACT_TEXT);
        JsonNode findings = objectMapper.readTree(raw).path("findings");

        List<String> types = new ArrayList<>();
        for (JsonNode f : findings) {
            assertThat(f.hasNonNull("riskType")).isTrue();
            assertThat(f.hasNonNull("quote")).isTrue();
            assertThat(f.hasNonNull("confidence")).isTrue();
            assertThat(f.path("confidence").asDouble()).isBetween(0.0, 1.0);
            assertThat(f.hasNonNull("reason")).isTrue();
            types.add(f.path("riskType").asText());
        }

        // 这份文本里应当能识别出若干种风险
        assertThat(types).contains("UNLIMITED_LIABILITY", "AUTO_RENEWAL");
    }

    @Test
    @DisplayName("同一输入两次调用结果完全一致（桩必须可复现）")
    void mockMustBeDeterministic() {
        String first = client.complete("你是合同风险条款识别助手，请输出 JSON", CONTRACT_TEXT);
        String second = client.complete("你是合同风险条款识别助手，请输出 JSON", CONTRACT_TEXT);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("要素抽取：value 与 quote 同样取自原文")
    void elementExtractionShouldUseRealText() throws Exception {
        String raw = client.complete("请执行要素抽取，输出 JSON", CONTRACT_TEXT);
        JsonNode elements = objectMapper.readTree(raw).path("elements");

        assertThat(elements.has("partyA")).isTrue();
        String partyA = elements.path("partyA").path("value").asText();
        assertThat(partyA).isEqualTo("北京某某科技有限公司");
        assertThat(CONTRACT_TEXT).contains(partyA);
    }

    @Test
    @DisplayName("含'演示幻觉'时桩会额外产出一条不存在的引文，用于演示降级路径")
    void hallucinationSampleShouldBeAlignableToFailure() throws Exception {
        String text = CONTRACT_TEXT + " 演示幻觉";
        String raw = client.complete("你是合同风险条款识别助手，请输出 JSON", text);
        JsonNode findings = objectMapper.readTree(raw).path("findings");

        List<String> quotes = new ArrayList<>();
        findings.forEach(f -> quotes.add(f.path("quote").asText()));

        var map = identityMap(text);
        long downgraded = quotes.stream()
                .map(q -> aligner.align(q, map))
                .filter(r -> !r.matched())
                .count();

        assertThat(downgraded)
                .withFailMessage("演示用的幻觉样本没有被对齐算法拦下来，降级路径演示不了")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("桩永远可用：它不依赖网络与额度，这正是它作为降级手段的价值")
    void mockShouldAlwaysBeAvailable() {
        assertThat(client.isAvailable()).isTrue();
        assertThat(client.providerName()).contains("mock");
    }

    @Test
    @DisplayName("空文本不抛异常，返回空结论")
    void blankTextShouldReturnEmptyFindings() throws Exception {
        String raw = client.complete("你是合同风险条款识别助手，请输出 JSON", "");
        assertThat(objectMapper.readTree(raw).path("findings")).isEmpty();
    }

    /** 构造恒等映射：本测试的文本已按归一化形态给出，不需要真实归一化。 */
    private static EvidenceAligner.OriginalMap identityMap(String text) {
        int[] offsets = new int[text.length()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = i;
        }
        return new EvidenceAligner.OriginalMap(text, offsets, text);
    }
}
