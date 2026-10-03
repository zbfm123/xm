package com.demo.contract.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用 Redis 配置：用一个带 TTL 的内存 Map 顶替 Redis。
 *
 * <p>实现选择说明（这里我改过一次方案，值得记下来）：
 * 最初想让真正的 {@code StringRedisTemplate} 连到一个假的 {@code RedisConnection} 上，
 * 但那个接口有二十多个方法，写了两百行还在报"未实现抽象方法"。
 * 而本项目实际只用到两个 Redis 操作——{@code set(key,value,ttl)} 和 {@code hasKey(key)}。
 * <p>所以改用 Mockito 只代理这三个方法（含 {@code opsForValue()}）。
 * <b>为两个操作去实现一个全套连接层，是典型的过度设计。</b>
 *
 * <p>行为是真实的：写入后能查到、TTL 到期后查不到——这样
 * {@code TokenBlacklist} 的过期逻辑才是被真的验证，而不只是"验证调用发生过"。
 */
@TestConfiguration
public class RedisTestConfig {

    /** 简易 TTL 存储。key -> (值, 过期时刻) */
    private static final Map<String, Entry> STORE = new ConcurrentHashMap<>();

    private record Entry(String value, Instant expireAt) {
        boolean alive() {
            return expireAt == null || expireAt.isAfter(Instant.now());
        }
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(template.opsForValue()).thenReturn(valueOps);

        // 无 TTL 的写入（AiResultCache.put 用的就是这个重载）
        doAnswer(inv -> {
            STORE.put(inv.getArgument(0), new Entry(inv.getArgument(1), null));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        // 带 TTL 的写入（TokenBlacklist 用的重载）
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            Duration ttl = inv.getArgument(2);
            Instant expireAt = (ttl == null || ttl.isZero() || ttl.isNegative())
                    ? null : Instant.now().plus(ttl);
            STORE.put(key, new Entry(value, expireAt));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // 读取：惰性过期，与真实 Redis 的语义一致
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Entry e = STORE.get(key);
            if (e == null) {
                return null;
            }
            if (!e.alive()) {
                STORE.remove(key);
                return null;
            }
            return e.value();
        }).when(valueOps).get(anyString());

        doAnswer(inv -> STORE.containsKey(inv.getArgument(0)))
                .when(template).hasKey(anyString());

        // 供 AiResultCache 使用：按前缀列出键 + 批量删除
        doAnswer(inv -> {
            String pattern = inv.getArgument(0);
            String regex = pattern.replace("*", ".*");
            STORE.entrySet().removeIf(e -> !e.getValue().alive());
            return STORE.keySet().stream()
                    .filter(k -> k.matches(regex))
                    .collect(java.util.stream.Collectors.toSet());
        }).when(template).keys(anyString());

        doAnswer(inv -> {
            java.util.Collection<String> keys = inv.getArgument(0);
            long removed = 0;
            for (String k : keys) {
                if (STORE.remove(k) != null) {
                    removed++;
                }
            }
            return removed;
        }).when(template).delete(any(java.util.Collection.class));

        return template;
    }

    /** 写入一个缓存条目，供测试预先置入 AI 结果。 */
    public static void put(String key, String value) {
        STORE.put(key, new Entry(value, null));
    }

    /** 每个测试前清空，避免测试之间互相影响。 */
    public static void clear() {
        STORE.clear();
    }

    /** 当前存活键数量，用于断言"过期条目不再计入"。 */
    public static long aliveCount() {
        STORE.entrySet().removeIf(e -> !e.getValue().alive());
        return STORE.size();
    }
}
