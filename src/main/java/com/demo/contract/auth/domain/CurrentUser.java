package com.demo.contract.auth.domain;

/**
 * 请求级当前用户上下文。
 *
 * <p>为什么用 ThreadLocal 而不是把 {@code CurrentUser} 作为参数层层透传：
 * 业务方法签名会被这个与业务无关的参数污染，而且<b>漏传的可能性很高</b>。
 * 放在上下文里，持久层拦截器（T-005）能自动取到 {@code tenantId}，无法"忘记"。
 *
 * <p>⚠️ 两条纪律：
 * <ol>
 *   <li>请求结束<b>必须</b>调用 {@link #clear()}。线程池会复用线程，不清理会导致下一个请求串号。</li>
 *   <li>取不到上下文时，调用方应当<b>失败</b>而不是退化为"查全部数据"（见 T-005）。</li>
 * </ol>
 */
public final class CurrentUser {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final Long tenantId;
    private final String username;
    private final Role role;

    private CurrentUser(Long userId, Long tenantId, String username, Role role) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.username = username;
        this.role = role;
    }

    public static void set(Long userId, Long tenantId, String username, Role role) {
        HOLDER.set(new CurrentUser(userId, tenantId, username, role));
    }

    /** 取当前用户；未登录时返回 null，由调用方决定如何处理。 */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前用户，未登录直接抛异常。
     *
     * <p>这是需要租户隔离的场景应该用的方法：<b>拿不到就失败，绝不返回 null 让上层去猜。</b>
     */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null) {
            throw new IllegalStateException("缺少登录上下文：该操作必须在已认证的请求内执行");
        }
        return u;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "CurrentUser{userId=" + userId + ", tenantId=" + tenantId
                + ", username='" + username + "', role=" + role + '}';
    }
}
