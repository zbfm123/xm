package com.demo.contract.auth.mapper;

import com.demo.contract.auth.domain.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户持久化。
 *
 * <p>注意：这是<b>唯一</b>允许在缺少 {@code tenantId} 条件时按用户名查询的地方——
 * 登录时还不知道用户属于哪个租户，必须先按用户名定位再取出租户。
 * 其余所有业务查询都必须带租户条件（T-005 的持久层拦截器负责强制注入）。
 *
 * <p>角色列 {@code role} 是 {@code VARCHAR}，MyBatis 默认的枚举处理器会把数据库字符串
 * 直接映射为同名枚举常量。若出现无法识别的值会抛异常而非留空——这正是我们要的：
 * <b>"角色解析不出来"必须显式失败，不能让一个角色为空的用户登录成功。</b>
 */
@Mapper
public interface UserMapper {

    @Select("""
            SELECT id, tenant_id, username, password_hash, display_name, role,
                   enabled, failed_count, locked_until, created_at
              FROM sys_user
             WHERE username = #{username}
            """)
    User findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, tenant_id, username, password_hash, display_name, role,
                   enabled, failed_count, locked_until, created_at
              FROM sys_user
             WHERE id = #{id}
            """)
    User findById(@Param("id") Long id);

    /** 登录成功后清零失败计数与锁定。 */
    @Update("""
            UPDATE sys_user
               SET failed_count = 0,
                   locked_until = NULL
             WHERE id = #{id}
            """)
    int clearLoginFailure(@Param("id") Long id);

    /** 记录一次登录失败；达到阈值时由 Service 一并写入 lockedUntil。 */
    @Update("""
            UPDATE sys_user
               SET failed_count = #{failedCount},
                   locked_until = #{lockedUntil}
             WHERE id = #{id}
            """)
    int updateLoginFailure(@Param("id") Long id,
                           @Param("failedCount") int failedCount,
                           @Param("lockedUntil") LocalDateTime lockedUntil);

    /** 供测试与初始化使用。 */
    @Insert("""
            INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
            VALUES (#{tenantId}, #{username}, #{passwordHash}, #{displayName}, #{role}, #{enabled})
            """)
    int insert(User user);
}
