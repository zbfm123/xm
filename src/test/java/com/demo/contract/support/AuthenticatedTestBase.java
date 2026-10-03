package com.demo.contract.support;

import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.auth.domain.Role;
import org.junit.jupiter.api.AfterEach;

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
}
