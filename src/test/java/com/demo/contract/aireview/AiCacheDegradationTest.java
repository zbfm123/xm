package com.demo.contract.aireview;

import com.demo.contract.aireview.client.MockAiClient;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>AI 缓存故障降级</b>：Redis 不可用时，抽取与审查必须仍然正常。
 *
 * <h2>为什么补这个</h2>
 *
 * {@code docs/02} 的选型表里，"缓存"那一行写着"**需处理 Redis 不可用时的降级**"——
 * 但在 2026-10-07 之前，{@code AiResultCache} 的 {@code get}/{@code put}
 * <b>没有 try/catch</b>：Redis 一挂，一次 {@code RedisConnectionFailureException}
 * 就会让抽取与审查整体失败。
 *
 * <p>这违反的是同一条纪律：<b>缓存只是省钱手段，不是正确性的一环。</b>
 * 为了"写缓存"把"审查"搞挂，是典型的负优化。
 * （项目 2 里对应的一句话是"缓存挂了不能影响挂号"。）
 *
 * <h2>怎么模拟</h2>
 *
 * 用一个会抛异常的 {@code AiResultCache}（{@code @Primary} 覆盖）：
 * 只让缓存读写失败，其它一切照常——这样测的是"缓存故障"，
 * 而不是"整个应用崩了"。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({RedisTestConfig.class, AiCacheDegradationTest.BrokenCacheConfig.class})
@Transactional
class AiCacheDegradationTest extends AuthenticatedTestBase {

    /**
     * 把底层的 {@code StringRedisTemplate} 换成一个"一用就抛"的桩。
     *
     * <h2>⚠️ 第一版写错了，这里值得记一笔</h2>
     *
     * 第一版是<b>继承 {@code AiResultCache} 并覆盖 {@code get}/{@code put}</b> 来抛异常。
     * 结果两个用例直接失败——因为<b>覆盖等于绕过了被测代码</b>：
     * 真正的 try/catch 就在 {@code get}/{@code put} 里面，
     * 我把它整个换掉了，于是测的是"抛异常且没人接"，而不是"降级生效"。
     *
     * <p><b>要测降级，就必须让异常抛在真实的 try 里面。</b>
     * 所以这里改成破坏更底层的 redis 客户端：
     * {@code AiResultCache} 的真实方法照常执行，异常从 {@code redis.opsForValue()}
     * 冒出来，正好落在它自己的 try 范围内。
     */
    @TestConfiguration
    static class BrokenCacheConfig {

        @Bean
        @Primary
        org.springframework.data.redis.core.StringRedisTemplate brokenRedisTemplate() {
            org.springframework.data.redis.core.StringRedisTemplate template =
                    org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
            org.mockito.Mockito.when(template.opsForValue())
                    .thenThrow(new RedisConnectionFailureException("模拟：Redis 连接不可用"));
            return template;
        }
    }

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private ElementExtractionService extractionService;
    @Autowired private TestPdfFactory pdfFactory;
    @Autowired private com.demo.contract.aireview.client.AiClient aiClient;
    @Autowired private AiResultCache aiResultCache;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private String contractText() {
        return String.join("\n",
                "采购合同（缓存降级样例）",
                "甲方：北京某某科技有限公司",
                "乙方：上海某某贸易有限公司",
                "签订日期：2026-01-01",
                "合同金额：128000.00元",
                "付款方式：另行约定。",
                "因本合同产生的一切损失均由乙方承担全部责任。",
                "争议解决：友好协商。");
    }

    private Long uploadAndParse() throws Exception {
        loginAsDemoTenant();
        byte[] pdf = pdfFactory.build(contractText().lines().toList());
        Long id = contractService.upload(new MockMultipartFile(
                        "file", "contract.pdf", "application/pdf", pdf), "缓存降级测试合同")
                .contract().id();
        var outcome = parsingService.parse(id);
        assertThat(outcome.success())
                .withFailMessage("测试数据解析失败: %s", outcome.message())
                .isTrue();
        return id;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("缓存抛异常时，要素抽取仍然成功（不会因缓存故障而整体失败）")
    void extractionShouldStillSucceedWhenCacheThrows() throws Exception {
        Long contractId = uploadAndParse();

        var summary = extractionService.extract(contractId);

        assertThat(summary)
                .as("缓存抛异常不能阻止抽取 —— 缓存只是省钱手段，不是正确性的一环")
                .isNotNull();
        assertThat(summary.total())
                .as("抽取结果本身必须有内容（证明真的走完了 AI 调用）")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("缓存抛异常时，AI 审查仍然成功")
    void reviewShouldStillSucceedWhenCacheThrows() throws Exception {
        Long contractId = uploadAndParse();

        var review = aiReviewService.review(contractId);

        assertThat(review)
                .as("缓存抛异常不能阻止审查")
                .isNotNull();
    }

    @Test
    @DisplayName("防假绿：底层 redis 确实在抛异常，而 AiResultCache 把它降级成了'未命中'")
    void cacheDegradesInsteadOfThrowing() {
        // ① 底层确实坏了（如果这里没坏，上面两条测试证明的就是别的东西）
        assertThatThrownBy(() -> aiResultCacheRedisOps())
                .as("底层 redis 客户端必须真的抛异常，否则这个测试没有意义")
                .isInstanceOf(RedisConnectionFailureException.class);

        // ② 但 AiResultCache 对外表现为"未命中"，而不是把异常抛出去
        assertThat(aiResultCache.get(AiResultCache.Operation.REVIEW, "any-key"))
                .as("缓存故障必须降级成 null（未命中），绝不能冒泡到调用方")
                .isNull();

        // ③ put 同理：不能抛
        aiResultCache.put(AiResultCache.Operation.REVIEW, "any-key", "{}");

        // ④ 反证：MockAiClient 本身是好的，所以上面两条服务级测试的通过
        //    确实来自"缓存故障被容忍"
        assertThat(aiClient).isInstanceOf(MockAiClient.class);
        assertThat(aiClient.isAvailable()).isTrue();
    }

    /** 直接捅一下底层 redis 客户端，确认它真的坏着。 */
    private Object aiResultCacheRedisOps() {
        return redisTemplate.opsForValue();
    }
}