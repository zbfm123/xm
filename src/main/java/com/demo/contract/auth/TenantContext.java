package com.demo.contract.auth;

import com.demo.contract.auth.domain.CurrentUser;

/**
 * 租户上下文的读取入口（不变式 I-01 的执行侧）。
 *
 * <p>存在的意义只有一个：<b>把"取当前租户"这件事收敛成一处，并且拿不到就失败。</b>
 *
 * <p>反例是这么写的：
 * <pre>
 * Long tenantId = CurrentUser.get() == null ? null : CurrentUser.get().getTenantId();
 * // 然后 SQL 里 tenant_id = #{tenantId} 变成 tenant_id = null
 * // 结果：查不到任何数据（还算幸运），或者被某个 if 分支跳过条件而查到全部（灾难）
 * </pre>
 * 所以这里 {@link #requireTenantId()} 直接抛异常，让"没有租户上下文"这件事
 * 在调用点就炸掉，而不是变成一个安静的错查询。
 */
public final class TenantContext {

    private TenantContext() {
    }

    /**
     * 取当前租户 id。
     *
     * @throws AuthException 缺少登录上下文时抛出 {@code TENANT_CONTEXT_MISSING}
     */
    public static Long requireTenantId() {
        CurrentUser user = CurrentUser.get();
        if (user == null || user.getTenantId() == null) {
            throw new AuthException(AuthErrorCode.TENANT_CONTEXT_MISSING,
                    "缺少租户上下文：该操作必须在已认证的请求内执行");
        }
        return user.getTenantId();
    }

    /** 是否处于已认证的租户上下文中。仅供需要分支处理的少数场景使用。 */
    public static boolean present() {
        CurrentUser user = CurrentUser.get();
        return user != null && user.getTenantId() != null;
    }
}
