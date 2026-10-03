package com.demo.contract.auth;

import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.auth.domain.IssuedToken;
import com.demo.contract.auth.domain.Role;
import com.demo.contract.auth.domain.User;
import com.demo.contract.auth.jwt.JwtService;
import com.demo.contract.auth.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 登录、登出与会话查询。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklist blacklist;
    private final LoginAttemptGuard guard;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenBlacklist blacklist,
                       LoginAttemptGuard guard) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.blacklist = blacklist;
        this.guard = guard;
    }

    /**
     * 登录。
     *
     * <p>校验顺序（顺序本身是设计的一部分）：
     * <ol>
     *   <li>锁定检查——<b>先于密码校验</b>。锁定期内连密码都不该验，否则锁定形同虚设。</li>
     *   <li>启用检查。</li>
     *   <li>密码校验。</li>
     * </ol>
     *
     * <p>密码错误时统一返回 {@code BAD_CREDENTIALS}，<b>不区分"用户不存在"与"密码错误"</b>：
     * 否则接口会变成账号探测器。
     */
    @Transactional
    public LoginResult login(String username, String rawPassword) {
        User user = userMapper.findByUsername(username);

        if (user == null) {
            // 注意：这里刻意不做"用户不存在"的差异化提示，也不在这里累加计数
            //（没有用户行可累加）。防枚举靠统一错误码，防爆破靠真实用户的锁定。
            throw new AuthException(AuthErrorCode.BAD_CREDENTIALS, "用户名或密码错误");
        }

        LocalDateTime lockUntil = guard.lockedUntil(user);
        if (lockUntil != null) {
            throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED,
                    "账号已锁定，请稍后再试", toEpochSeconds(lockUntil));
        }

        if (!user.isEnabled()) {
            throw new AuthException(AuthErrorCode.ACCOUNT_DISABLED, "账号已停用");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            boolean locked = guard.recordFailure(user);
            if (locked) {
                LocalDateTime until = LocalDateTime.now();
                throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED,
                        "连续失败次数过多，账号已锁定", toEpochSeconds(until));
            }
            throw new AuthException(AuthErrorCode.BAD_CREDENTIALS, "用户名或密码错误");
        }

        guard.recordSuccess(user);

        IssuedToken issued = jwtService.issue(
                user.getId(), user.getTenantId(), user.getRole(), user.getDisplayName());

        return new LoginResult(issued, user);
    }

    /**
     * 登出：把令牌写入黑名单。
     *
     * <p>对「令牌本身已过期」或「已被登出」的情况<b>静默成功</b>——
     * 登出是幂等操作，重复登出不该报错。
     */
    public void logout(String token) {
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(token);
        } catch (JwtService.InvalidTokenException e) {
            return;
        }
        blacklist.revoke(parsed.jti(), parsed.expiresAt());
    }

    /** 查询当前登录用户，用于前端刷新页面后恢复状态。 */
    public User currentUser(CurrentUser context) {
        User user = userMapper.findById(context.getUserId());
        if (user == null || !user.isEnabled()) {
            throw new AuthException(AuthErrorCode.TOKEN_INVALID, "用户不存在或已停用");
        }
        // 租户必须一致：令牌里的租户和库里的租户不符说明数据被改过，直接拒绝
        if (!user.getTenantId().equals(context.getTenantId())) {
            throw new AuthException(AuthErrorCode.TOKEN_INVALID, "租户信息不一致");
        }
        return user;
    }

    private Long toEpochSeconds(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 登录结果。
     *
     * @param token 已签发的令牌
     * @param user  用户实体（<b>仅限服务端内部使用</b>，其中含密码哈希，不得直接序列化返回）
     */
    public record LoginResult(IssuedToken token, User user) {

        public String formattedLockHint() {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
        }

        public Role role() {
            return user.getRole();
        }
    }
}
