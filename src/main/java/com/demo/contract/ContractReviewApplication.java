package com.demo.contract;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 合同智能审查平台 · 启动类
 *
 * <p>架构定位：模块化单体（决策 D-01）。包结构即模块边界：
 * <pre>
 *   auth       登录、JWT、租户上下文          ← 被依赖，不依赖别人
 *   parse      上传、文本提取、保坐标归一化
 *   extract    要素抽取 + 证据对齐算法
 *   rule       确定性规则引擎（不触网、不调模型）
 *   aireview   AI 风险审查（只产出候选结论）
 *   workflow   状态机、幂等、人工复核、降级   ← 唯一允许"知道所有人"的模块
 * </pre>
 *
 * <p>边界纪律（见 docs/02-architecture.md#模块边界）：
 * <ul>
 *   <li>{@code rule} 与 {@code aireview} 互不调用，只在 {@code workflow} 汇合</li>
 *   <li>跨模块禁止直接读对方的表，只能走接口/领域事件</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.demo.contract.**.mapper")
public class ContractReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractReviewApplication.class, args);
    }
}
