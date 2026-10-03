package com.demo.contract.auth;

import com.demo.contract.auth.domain.User;
import com.demo.contract.auth.mapper.UserMapper;
import com.demo.contract.config.JwtProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 登录失败计数与账号锁定。
 *
 * <p>为什么要有这个东西：没有任何限速的登录接口等于把密码交给暴力破解。
 * 阈值与锁定时长来自配置（{@code app.jwt.max-failures} / {@code lock-minutes}）。
 *
 * <p>计数持久化在 {@code sys_user.failed_count} 而不是只放 Redis：
 * <b>锁定状态必须能扛住 Redis 重启</b>——否则清一次缓存就等于给攻击者重置了机会。
 * 如果后续引入 Redis，可以在这一层加滑动窗口以支持"按 IP 限速"，但落库这条不能去掉。
 */
@Component
public class LoginAttemptGuard {

    private final UserMapper userMapper;
    private final JwtProperties properties;

    public LoginAttemptGuard(UserMapper userMapper, JwtProperties properties) {
        this.userMapper = userMapper;
        this.properties = properties;
    }

    /**
     * 判断账号当前是否处于锁定状态。
     *
     * @return 尚有锁定时返回解锁时刻；未锁定返回 null
     */
    public LocalDateTime lockedUntil(User user) {
        LocalDateTime until = user.getLockedUntil();
        if (until == null) {
            return null;
        }
        return until.isAfter(LocalDateTime.now()) ? until : null;
    }

    /**
     * 记录一次失败。
     *
     * @return 本次失败后是否触发了锁定
     */
    public boolean recordFailure(User user) {
        int next = user.getFailedCount() + 1;
        LocalDateTime lockUntil = null;
        if (next >= properties.maxFailures()) {
            lockUntil = LocalDateTime.now().plusMinutes(properties.lockMinutes());
        }
        userMapper.updateLoginFailure(user.getId(), next, lockUntil);
        return lockUntil != null;
    }

    public void recordSuccess(User user) {
        if (user.getFailedCount() > 0 || user.getLockedUntil() != null) {
            userMapper.clearLoginFailure(user.getId());
        }
    }
}
