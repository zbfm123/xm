package com.demo.contract.aireview;

import com.demo.contract.aireview.client.MockAiClient;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 审查结果缓存是否<b>真的省下了调用</b>。
 *
 * <h2>为什么要专门写这个测试类</h2>
 *
 * 2026-10-07 发现 {@link AiResultCache} 是一个"只有删除、没有读写"的组件：
 *
 * <ul>
 *   <li>{@code put()} 全项目无人调用 → 缓存<b>从来没被写入过</b>；</li>
 *   <li>{@code get()} 全项目无人调用 → 因此<b>永远不可能命中</b>；</li>
 *   <li>唯一的生产调用是 {@code ContractService.delete()} 里的 {@code evictByTextHash}。</li>
 * </ul>
 *
 * <p>结果是：配置写着 {@code cache-enabled: true}、缓存类写得很完整、
 * 删合同时还会"清理缓存"，<b>但每次审查依然真的调 AI</b>——
 * 一个看起来在工作、实际一次都没生效的缓存。
 *
 * <p>这类问题<b>从单元测试里看不出来</b>：缓存的 put/get 都测过，
 * 只是没有任何生产代码调用它们。<b>"组件有测试"不等于"功能接上了"。</b>
 *
 * <h2>断言的重点</h2>
 *
 * 关键不是"缓存里有值"（那只证明 put 执行过），而是
 * <b>第二次审查不再调用 AI</b>（那才证明钱省下来了）。
 *
 * <p>
 * ⚠️ <b>必须加 {@code @Transactional}</b>——第一版没加，结果整类测试的 4 个用例失败：
 * 本类上传的合同**真的提交进了库**，而 {@code ContractServiceTest} 里的计数断言
 * 是写死的（{@code list(...).total() == 6}）。凭空多出来的合同把它们全打破了：
 * <pre>
 *   pagingParametersShouldBeClamped       Expected size: 1 but was: 3
 *   listShouldSupportFiltersAndPaging     expected: 6L but was: 8L
 * </pre>
 *
 * <p>关键判断依据是：{@code ContractParsingService.parse()} 是普通
 * {@code @Transactional}（不是 {@code REQUIRES_NEW}），
 * 因此本类开事务就能把这些写入全部回滚掉。
 * <b>如果哪天 parse 改成 REQUIRES_NEW，这个测试就会重新开始污染别人</b>——
 * 所以这条依赖值得写在这里。
 *
 * <p>缓存那边不受影响：内存 Redis 是静态 Map、不参与事务，
 * 但基类的 {@code @BeforeEach} 每次都会清它。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class AiResultCacheIntegrationTest extends AuthenticatedTestBase {

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private AiResultCache aiResultCache;
    @Autowired private TestPdfFactory pdfFactory;
    @Autowired private ObjectMapper objectMapper;

    /** 注入的是唯一的 AiClient bean；测试 profile 下它就是 Mock 桩。 */
    @Autowired private com.demo.contract.aireview.client.AiClient aiClient;

    private String contractText() {
        return String.join("\n",
                "采购合同（缓存测试样例）",
                "甲方：北京某某科技有限公司",
                "乙方：上海某某贸易有限公司",
                "签订日期：2026-01-01",
                "合同金额：128000.00元",
                "付款方式：另行约定。",
                "因本合同产生的一切损失均由乙方承担全部责任。",
                "争议解决：友好协商。");
    }

    /**
     * 上传并解析一份合同，返回合同 id。
     *
     * <p>照抄 {@code AiReviewIntegrationTest} 里的同一段样板：
     * 用 {@code pdfFactory.build(lines)} 造 PDF、{@code upload(...).contract().id()} 拿 id、
     * 并**断言解析成功**（解析失败的话后面的审查断言会以误导性的方式失败）。
     */
    private Long uploadAndParse() throws Exception {
        loginAsDemoTenant();
        List<String> lines = contractText().lines().toList();
        byte[] pdf = pdfFactory.build(lines);
        Long id = contractService.upload(new MockMultipartFile(
                        "file", "contract.pdf", "application/pdf", pdf), "缓存测试合同")
                .contract().id();

        var outcome = parsingService.parse(id);
        assertThat(outcome.success())
                .withFailMessage("测试数据解析失败: %s", outcome.message())
                .isTrue();
        return id;
    }

    private MockAiClient mockClient() {
        assertThat(aiClient)
                .as("测试 profile 下 AiClient 应当是 MockAiClient；"
                        + "如果不是，本测试的调用计数断言就没有意义")
                .isInstanceOf(MockAiClient.class);
        return (MockAiClient) aiClient;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 核心断言：同一份合同审查两次，AI 只被调用一次（缓存真的省钱）")
    void secondReviewShouldHitCacheAndSkipAiCall() throws Exception {
        Long contractId = uploadAndParse();
        MockAiClient client = mockClient();

        int before = client.callCount();

        // 第一次：必然真的调 AI
        var first = aiReviewService.review(contractId);
        int afterFirst = client.callCount();

        assertThat(afterFirst - before)
                .as("第一次审查必须真的调用 AI（缓存里还没有东西）")
                .isEqualTo(1);

        // 第二次：必须命中缓存，不再调 AI
        var second = aiReviewService.review(contractId);
        int afterSecond = client.callCount();

        assertThat(afterSecond)
                .as("第二次审查**不能**再调用 AI —— 这正是缓存存在的唯一理由。"
                        + "如果这里又涨了，说明缓存还是【只有删除、没有读写】的老样子")
                .isEqualTo(afterFirst);

        // 结论条数也必须一致：缓存命中的结果不能和真实调用不一样
        assertThat(second.total())
                .as("命中缓存得到的结论条数必须与首次一致")
                .isEqualTo(first.total());
    }

    @Test
    @DisplayName("缓存里确实写入了模型响应（而不是靠别的机制省下调用）")
    void cacheShouldBePopulatedAfterReview() throws Exception {
        Long contractId = uploadAndParse();
        loginAsDemoTenant();

        Contract contract = contractService.get(contractId);
        String textHash = contract.getTextHash();
        assertThat(textHash).as("合同必须有 textHash，缓存键就靠它").isNotNull();

        assertThat(aiResultCache.get(textHash))
                .as("审查之前不该有缓存").isNull();

        aiReviewService.review(contractId);

        String cached = aiResultCache.get(textHash);
        assertThat(cached)
                .as("审查之后缓存里必须有模型原始响应")
                .isNotNull();
        // 缓存的是原始响应（含 findings 字段），不是解析后的结论
        JsonNode node = objectMapper.readTree(cached);
        assertThat(node.path("findings").isArray())
                .as("缓存内容应当是模型的原始响应（含 findings 数组）")
                .isTrue();
    }

    @Test
    @DisplayName("删合同时清理缓存，缓存随之消失（原有的删除语义不能被破坏）")
    void deletingContractShouldEvictCache() throws Exception {
        Long contractId = uploadAndParse();
        loginAsDemoTenant();

        String textHash = contractService.get(contractId).getTextHash();
        aiReviewService.review(contractId);
        assertThat(aiResultCache.get(textHash)).isNotNull();

        contractService.delete(contractId);

        assertThat(aiResultCache.get(textHash))
                .as("删除合同必须清掉它的 AI 缓存，否则下次上传同一份合同会命中过期结论"
                        + "（这正是文档 D-13 记录的那个坑）")
                .isNull();
    }
}
