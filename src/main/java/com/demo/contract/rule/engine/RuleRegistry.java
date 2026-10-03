package com.demo.contract.rule.engine;

import com.demo.contract.rule.domain.Rule;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则注册表。
 *
 * <p>Spring 会把所有 {@link Rule} 实现注入进来，因此新增规则只需要加一个
 * {@code @Component} 类，<b>不需要改引擎</b>。这是"引擎与规则分离"的落点。
 *
 * <p>启动时校验规则编码唯一：重复编码会让结论无法区分来源，
 * 而且这种错误在运行时很难发现（两条规则都跑，结论混在一起）。
 * <b>启动就失败，比运行到一半发现好得多。</b>
 *
 * <p>排序按 {@code code} 字典序，保证输出顺序稳定——
 * 否则"同输入同输出"（I-03）会在顺序上被破坏，对比两次结论时会看到无意义的差异。
 */
@Component
public class RuleRegistry {

    private final List<Rule> rules;

    public RuleRegistry(List<Rule> discoveredRules) {
        Map<String, Rule> byCode = new HashMap<>();
        for (Rule rule : discoveredRules) {
            Rule previous = byCode.putIfAbsent(rule.code(), rule);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "规则编码重复: %s 被 %s 与 %s 同时使用。编码必须全局唯一，否则结论无法区分来源。",
                        rule.code(), previous.getClass().getName(), rule.getClass().getName()));
            }
            if (rule.code() == null || rule.code().isBlank()) {
                throw new IllegalStateException(
                        "规则编码不能为空: " + rule.getClass().getName());
            }
        }
        this.rules = byCode.values().stream()
                .sorted(Comparator.comparing(Rule::code))
                .toList();
    }

    public List<Rule> all() {
        return rules;
    }

    public int size() {
        return rules.size();
    }
}
