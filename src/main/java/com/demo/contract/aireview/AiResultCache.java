package com.demo.contract.aireview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * AI 审查结果缓存。
 *
 * <p>缓存键：{@code ai:result:<textHash>:<promptVersion>:<modelVersion>}。
 *
 * <p>键里带上提示词与模型版本是必须的：否则改了提示词之后，
 * 仍然会命中旧提示词产生的结论，而且<b>看起来完全正常</b>——
 * 这类"缓存没失效但也没报错"的问题最难排查。
 *
 * <p>与 {@code TokenBlacklist} 相反，这里的条目<b>刻意不带 TTL</b>：
 * 审查结论是长期资产，不像令牌那样会自然过期。
 * 代价是必须能定向清理——这正是 T-008 删除合同要做的事，
 * 也是文档 D-13 记录的"删了库不清缓存会返回过期结论"那个坑。
 */
@Component
public class AiResultCache {

    private static final Logger log = LoggerFactory.getLogger(AiResultCache.class);
    private static final String PREFIX = "ai:result:";

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String promptVersion;
    private final String model;

    public AiResultCache(StringRedisTemplate redis,
                         @Value("${app.ai.cache-enabled}") boolean enabled,
                         @Value("${app.ai.prompt-version}") String promptVersion,
                         @Value("${app.ai.model}") String model) {
        this.redis = redis;
        this.enabled = enabled;
        this.promptVersion = promptVersion;
        this.model = model;
    }

    private String key(String textHash) {
        return PREFIX + textHash + ":" + promptVersion + ":" + model;
    }

    public void put(String textHash, String json) {
        if (!enabled || textHash == null) {
            return;
        }
        redis.opsForValue().set(key(textHash), json);
    }

    public String get(String textHash) {
        if (!enabled || textHash == null) {
            return null;
        }
        return redis.opsForValue().get(key(textHash));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 按前缀清理某个文本哈希的全部缓存条目（所有提示词/模型版本）。
     *
     * <p>为什么用 {@code KEYS} 而不是维护一个索引集合：本项目缓存条目的基数很小
     * （每个租户的合同数量级），{@code KEYS} 的开销可以接受。
     * <b>若将来缓存条目上万，这里必须换成 SCAN 或维护索引 Set</b>——
     * {@code KEYS} 会阻塞 Redis 单线程。这是一个明确的已知取舍。
     *
     * @return 实际删除的键数量
     */
    public int evictByTextHash(String textHash) {
        if (textHash == null) {
            return 0;
        }
        // 用通配符覆盖所有版本组合
        Set<String> keys = redis.keys(PREFIX + textHash + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        int count = removed == null ? 0 : removed.intValue();
        log.info("已清理 AI 缓存 {} 条 (textHash={})", count, textHash);
        return count;
    }
}
