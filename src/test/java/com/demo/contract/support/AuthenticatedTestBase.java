package com.demo.contract.support;

import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.auth.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * 需要登录上下文的集成测试基类。
 *
 * <p>为什么需要它：{@code ContractService} 从 {@link CurrentUser} 取租户，
 * 而不是从方法参数取——这是刻意的（调用方不该有机会"传错租户"）。
 * 因此测试必须显式建立上下文，这也顺带证明了"没有上下文就一定失败"。
 *
 * <p>{@code @AfterEach} 清理是硬性要求：ThreadLocal 在测试间也会串号。
 */
public abstract class AuthenticatedTestBase {

    protected static final Long DEMO_TENANT = 1L;
    protected static final Long OTHER_TENANT = 2L;

    /** 以演示租户身份执行后续代码。 */
    protected void loginAsDemoTenant() {
        CurrentUser.set(101L, DEMO_TENANT, "staff01", Role.LEGAL_STAFF);
    }

    /** 以对照租户身份执行后续代码，用于跨租户隔离验证。 */
    protected void loginAsOtherTenant() {
        CurrentUser.set(201L, OTHER_TENANT, "other01", Role.LEGAL_STAFF);
    }

    @AfterEach
    void clearContext() {
        CurrentUser.clear();
    }

    /**
     * 每个测试前清掉 AI 结果缓存（{@link RedisTestConfig} 的内存 Redis）。
     *
     * <h2>为什么必须在这里集中做</h2>
     *
     * 2026-10-07 给 {@code AiReviewService.review()} 接上"先查缓存、命中就跳过 AI"之后，
     * 才发现一个原有的卫生漏洞：
     *
     * <ul>
     *   <li>内存 Redis 是 <b>static</b> 的，<b>不受 {@code @Transactional} 回滚影响</b>；</li>
     *   <li>而原先只有 {@code AuthIntegrationTest} 与 {@code ContractApiTest}
     *       在 {@code @BeforeEach} 里调了 {@code RedisTestConfig.clear()}；</li>
     *   <li>会走 AI 审查的 4 个测试类（本基类的子类）<b>一个都没清</b>。</li>
     * </ul>
     *
     * <p>接上缓存读写之前，这没造成问题——因为缓存<b>从来没被写入过</b>。
     * 一旦开始写入，前一个测试缓存的 AI 响应就会被后一个测试命中，
     * 表现为"AI 没被调用"这类**跨测试污染**，而且只在特定执行顺序下出现。
     *
     * <p>放在基类里而不是逐个测试类里加：这四个类都继承本类，
     * <b>一处修改覆盖全部，也不会有人忘记加</b>。
     * （这正是"靠人记得的事一定会忘"的同一个教训。）
     */
    @BeforeEach
    void clearAiResultCache() {
        RedisTestConfig.clear();
    }
}
