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
 * <p><b>⚠️ 缓存故障必须降级（补于 2026-10-07）</b>：
 * {@code get} 失败当作"未命中"继续调 AI，{@code put} 失败只记日志。
 * 在此之前这两个方法没有 try/catch —— Redis 一挂，抽取与审查会直接失败。
 * <b>缓存只是省钱手段，不是正确性的一环；为了写缓存把审查搞挂是负优化。</b>
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

    /**
     * 操作维度：抽取与审查是<b>两种不同形状的模型响应</b>，必须分键存放。
     *
     * <h2>⚠️ 为什么必须有这一层（踩过的坑）</h2>
     *
     * 2026-10-07 给 {@code ElementExtractionService.extract()} 接上缓存时，
     * 它和 {@code AiReviewService.review()} 用的是同一个键（只按 textHash）。
     * 结果是<b>两者互相覆盖</b>：
     *
     * <pre>
     *   extract()  -> 缓存里放的是 {"elements":[...]}
     *   review()   -> 读到的却是抽取结果，解析 {"findings"} 时找不到字段
     *              -> 抛 SCHEMA_INVALID -> 整条链路降级为 AI_UNAVAILABLE
     * </pre>
     *
     * <p>表现很有迷惑性：{@code AiReviewIntegrationTest} 与
     * {@code ReviewTaskIntegrationTest} 共 9 个用例失败，报的是
     * "响应缺少 findings 字段"——看起来像模型或提示词的问题，
     * 实际是**两个功能抢同一个缓存键**。
     *
     * <p>所以键里除了文本、提示词版本、模型，还必须带上<b>操作</b>。
     * 用枚举而不是字符串常量，是为了让"新增一种 AI 操作"时必须显式选择命名空间，
     * 而不是顺手复用别人的键。
     */
    public enum Operation {
        /** 要素抽取。 */
        EXTRACT("extract"),
        /** 风险审查。 */
        REVIEW("review");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    private String key(Operation op, String textHash) {
        return PREFIX + op.tag() + ":" + textHash + ":" + promptVersion + ":" + model;
    }

    public void put(Operation op, String textHash, String json) {
        if (!enabled || textHash == null || op == null) {
            return;
        }
        try {
            redis.opsForValue().set(key(op, textHash), json);
        } catch (RuntimeException e) {
            // ⚠️ 缓存写失败只记日志，绝不冒泡。
            //
            // Redis 不可用时不能让 AI 审查失败：缓存只是省钱手段，
            // 不是正确性的一环。为了"写缓存"把"审查"搞挂是典型的负优化。
            // 这与项目 2 里"缓存挂了不能影响挂号"是同一条纪律。
            log.warn("写 AI 缓存失败，忽略（本次结果仍正常返回，只是下次不会命中）: op={} textHash={} 原因={}",
                    op, textHash, e.toString());
        }
    }

    public String get(Operation op, String textHash) {
        if (!enabled || textHash == null || op == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(key(op, textHash));
        } catch (RuntimeException e) {
            // ⚠️ 缓存读失败 = 当作"未命中"，继续去调 AI。
            //
            // 这是**降级**而不是容错：Redis 挂了，功能照常，
            // 代价只是重新开始真的调模型（成本上升，但结果正确）。
            log.warn("读 AI 缓存失败，按未命中处理（将真实调用 AI）: op={} textHash={} 原因={}",
                    op, textHash, e.toString());
            return null;
        }
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
        // 用通配符覆盖**所有操作 + 所有提示词/模型版本**组合。
        //
        // ⚠️ 中间那个 `*` 是必须的：键的格式是
        //      ai:result:<operation>:<textHash>:<promptVersion>:<model>
        //    所以 <textHash> 前面还有一段 operation。
        //    少了这个通配符，删除就变成"永远匹配不到"——
        //    而它的表现是**静默失效**（不报错、返回 0），
        //    直到有人重新上传同一份合同命中过期结论才发现。
        //    这个坑 2026-10-07 真的踩了一次（加 operation 命名空间时忘了同步这里），
        //    由 ContractServiceTest.deleteShouldEvictAiCacheForItsTextHash 抓到。
        Set<String> keys = redis.keys(PREFIX + "*:" + textHash + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long removed = redis.delete(keys);
        int count = removed == null ? 0 : removed.intValue();
        log.info("已清理 AI 缓存 {} 条 (textHash={})", count, textHash);
        return count;
    }
}
