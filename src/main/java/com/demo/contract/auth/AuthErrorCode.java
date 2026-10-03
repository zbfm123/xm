package com.demo.contract.auth;

/**
 * 认证相关错误码。
 *
 * <p>为什么不用一个笼统的"登录失败"：
 * 用户能做的事不同——{@code BAD_CREDENTIALS} 要重新输入，{@code ACCOUNT_LOCKED} 只能等待，
 * {@code TOKEN_EXPIRED} 要重新登录，{@code TOKEN_REVOKED} 说明是被主动登出的。
 * <b>错误码的价值在于让调用方知道"接下来该干什么"。</b>
 */
public enum AuthErrorCode {

    /** 用户名或密码错误。<b>刻意不区分"用户不存在"与"密码错误"</b>，避免账号枚举。 */
    BAD_CREDENTIALS,
    /** 账号被锁定，需等待 unlockAt。 */
    ACCOUNT_LOCKED,
    /** 账号已停用。 */
    ACCOUNT_DISABLED,
    /** 令牌过期。 */
    TOKEN_EXPIRED,
    /** 令牌无效（签名不符/结构损坏/issuer 不匹配）。 */
    TOKEN_INVALID,
    /** 令牌已被登出（命中黑名单）。 */
    TOKEN_REVOKED,
    /** 请求需要登录但没带令牌。 */
    UNAUTHENTICATED,
    /** 缺少登录上下文——这是实现缺陷，不是用户问题。 */
    TENANT_CONTEXT_MISSING
}
