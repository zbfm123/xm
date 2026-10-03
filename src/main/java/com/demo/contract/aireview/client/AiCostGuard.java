package com.demo.contract.aireview.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调用成本与次数守卫。
 *
 * <p><b>存在的理由不是"额度不够"，而是"调试时会把额度烧光"。</b>
 * 真实风险是这样发生的：缓存没生效，跑一次集成测试就是 20 次调用，跑十遍就是 200 次。
 * 等发现时额度已经见底。
 *
 * <p>因此设了两道硬上限：
 * <ol>
 *   <li><b>单份合同调用次数上限</b>——超限直接失败，让问题在第一次异常调用时就暴露</li>
 *   <li><b>日预算上限</b>——把调用次数换算成估算金额累计</li>
 * </ol>
 *
 * <p>计数放在内存里（{@link AtomicInteger} / {@link AtomicLong}）：
 * 演示环境是单实例，进程重启清零是可接受的。
 * <b>若要多实例部署，这里必须换成 Redis 计数</b>——否则每个实例各算一份，限额形同虚设。
 *
 * <p>估算规则：按"输入 2 字 ≈ 1 token、输出 1 token ≈ 1 元/百万"粗算。
 * 目的不是精确计费，而是<b>在超支前刹住车</b>。
 */
@Component
public class AiCostGuard {

    private static final Logger log = LoggerFactory.getLogger(AiCostGuard.class);

    /** 极粗略的单价估算（元 / 千 token），仅用于"别烧光"这个目的。 */
    private static final double CNY_PER_1K_TOKENS = 0.003;

    private final int maxCallsPerContract;
    private final double dailyBudgetCny;
    private final boolean enforceLimits;

    private final AtomicInteger callsInCurrentContract = new AtomicInteger();
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final AtomicLong estimatedTokens = new AtomicLong();
    private final AtomicLong estimatedCostMilliCny = new AtomicLong();

    public AiCostGuard(@Value("${app.ai.max-calls-per-contract}") int maxCallsPerContract,
                       @Value("${app.ai.daily-budget-cny}") double dailyBudgetCny,
                       @Value("${app.ai.enabled}") boolean enabled) {
        this.maxCallsPerContract = maxCallsPerContract;
        this.dailyBudgetCny = dailyBudgetCny;
        // Mock 模式下不限制：桩调用不花钱，限制它只会妨碍开发
        this.enforceLimits = enabled;
    }

    /**
     * 开始处理一份新合同，重置单合同计数。
     *
     * <p>刻意做成显式调用而不是自动检测：让"新合同开始"这件事在代码里可见，
     * 避免计数被意外跨合同累计。
     */
    public void beginContract() {
        callsInCurrentContract.set(0);
    }

    /**
     * 申请一次调用额度。
     *
     * @throws AiCallException 超出任一上限时抛出，<b>直接失败而不是继续调用</b>
     */
    public void acquireOrThrow() {
        if (!enforceLimits) {
            totalCalls.incrementAndGet();
            return;
        }

        int used = callsInCurrentContract.get();
        if (used >= maxCallsPerContract) {
            throw new AiCallException(AiErrorCode.CALL_LIMIT_EXCEEDED,
                    String.format("单份合同调用次数已达上限 %d 次，已拒绝继续调用。"
                            + "这通常意味着缓存未生效或存在重复调用，请先排查", maxCallsPerContract));
        }

        if (estimatedCostYuan() >= dailyBudgetCny) {
            throw new AiCallException(AiErrorCode.BUDGET_EXCEEDED,
                    String.format("日预算已用尽（上限 %.2f 元，已估算 %.4f 元），已拒绝调用。"
                            + "注意：这里必须明确失败，绝不能降级成'未发现风险'",
                    dailyBudgetCny, estimatedCostYuan()));
        }

        callsInCurrentContract.incrementAndGet();
        totalCalls.incrementAndGet();
    }

    /** 记录一次调用的粗略 token 消耗。 */
    public void recordUsage(int inputChars, int outputChars) {
        // 中文约 1.5~2 字 / token，取 2 字/token 做保守估算
        long tokens = (inputChars / 2L) + Math.max(1, outputChars / 2L);
        estimatedTokens.addAndGet(tokens);
        long milli = (long) Math.ceil(tokens / 1000.0 * CNY_PER_1K_TOKENS * 1000);
        estimatedCostMilliCny.addAndGet(milli);
    }

    public double estimatedCostYuan() {
        return estimatedCostMilliCny.get() / 1000.0;
    }

    public int totalCalls() {
        return totalCalls.get();
    }

    public int callsInCurrentContract() {
        return callsInCurrentContract.get();
    }

    public long estimatedTokensTotal() {
        return estimatedTokens.get();
    }

    public boolean limitsEnforced() {
        return enforceLimits;
    }

    /** 调试台与健康检查用。 */
    public String summary() {
        if (!enforceLimits) {
            return String.format("Mock 模式（不限额）：累计调用 %d 次", totalCalls.get());
        }
        return String.format("累计调用 %d 次，估算消耗 %.4f 元 / 预算 %.2f 元，单合同上限 %d 次",
                totalCalls.get(), estimatedCostYuan(), dailyBudgetCny, maxCallsPerContract);
    }

    void logLimitHit(AiErrorCode code) {
        log.warn("AI 调用被限额拒绝: code={} {}", code, summary());
    }
}
