package com.demo.contract.rule.engine;

import com.demo.contract.rule.domain.Rule;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleFinding;
import com.demo.contract.rule.domain.RuleOutcome;
import com.demo.contract.rule.domain.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎：遍历规则、汇合结论。
 *
 * <p>三条设计决定，都写在这里以免日后被"顺手重构"掉：
 *
 * <ol>
 *   <li><b>单条规则出错不中断整体。</b> 一条规则的实现缺陷不该让整份合同校验失效。
 *       但该条必须显式标记为出错（{@code UNDETERMINED} + {@code errorMessage}），
 *       <b>不能静默跳过</b>——静默跳过等于向用户隐藏了"这条判断没有产出"。</li>
 *
 *   <li><b>不联网、不读时钟。</b> 本模块存在的意义就是确定性判断不付概率代价。
 *       需要时间时从 {@link RuleContext#today()} 取。</li>
 *
 *   <li><b>输出顺序稳定。</b> 按规则编码排序，保证同样输入两次执行的结果逐条相同。</li>
 * </ol>
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleRegistry registry;

    public RuleEngine(RuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行全部规则。
     *
     * <p><b>本方法不抛业务异常。</b> 单条规则的问题被收敛为一条出错结论，
     * 让调用方总能拿到"这次校验产出了什么、哪些没产出"的完整视图。
     */
    public RuleExecutionResult execute(RuleContext context) {
        List<RuleFinding> findings = new ArrayList<>(registry.size());
        int errorCount = 0;

        for (Rule rule : registry.all()) {
            RuleOutcome outcome;
            try {
                outcome = rule.evaluate(context);
                if (outcome == null) {
                    // 返回 null 属于实现缺陷：接口要求必须显式返回三态之一
                    throw new IllegalStateException("规则返回了 null，必须显式返回三态之一");
                }
            } catch (RuntimeException e) {
                errorCount++;
                log.warn("规则执行失败，已标记为出错结论: rule={} error={}",
                        rule.code(), e.toString());
                findings.add(RuleFinding.error(rule, e));
                continue;
            }
            findings.add(RuleFinding.of(rule, outcome));
        }

        RuleExecutionResult result = new RuleExecutionResult(findings, errorCount);
        log.info("规则校验完成: 共{}条 命中{}条 无法判定{}条 出错{}条",
                result.total(), result.hitCount(), result.undeterminedCount(), errorCount);
        return result;
    }

    /**
     * 一次校验的汇总。
     *
     * <p>把三个计数单独暴露出来，而不是让调用方遍历统计：
     * <b>"无法判定"的条数是本系统最重要的健康指标之一</b>——
     * 它偏高意味着抽取质量差或规则依赖的字段没有产出，
     * 应当在界面上显著提示，而不是埋在列表里。
     */
    public record RuleExecutionResult(List<RuleFinding> findings, int errorCount) {

        public RuleExecutionResult {
            findings = List.copyOf(findings);
        }

        public int total() {
            return findings.size();
        }

        public long hitCount() {
            return findings.stream().filter(RuleFinding::isHit).count();
        }

        public long passCount() {
            return findings.stream().filter(f -> f.result() == RuleResult.PASS).count();
        }

        public long undeterminedCount() {
            return findings.stream().filter(RuleFinding::isUndetermined).count();
        }

        public List<RuleFinding> hits() {
            return findings.stream().filter(RuleFinding::isHit).toList();
        }

        public List<RuleFinding> undetermined() {
            return findings.stream().filter(RuleFinding::isUndetermined).toList();
        }

        /** 是否存在规则自身出错——调用方应当据此告警，而不是当作正常结果。 */
        public boolean hasRuleErrors() {
            return errorCount > 0;
        }
    }
}
