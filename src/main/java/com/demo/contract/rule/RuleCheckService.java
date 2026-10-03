package com.demo.contract.rule;

import com.demo.contract.auth.TenantContext;
import com.demo.contract.extract.DeterministicElementExtractor;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.parse.domain.ContractText;
import com.demo.contract.parse.mapper.ContractMapper;
import com.demo.contract.rule.domain.RuleContext;
import com.demo.contract.rule.domain.RuleFindingEntity;
import com.demo.contract.rule.domain.RuleResult;
import com.demo.contract.rule.engine.RuleEngine;
import com.demo.contract.rule.mapper.RuleFindingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 规则校验编排：取正文 → 抽要素 → 跑规则 → 落库。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>没有正文时拒绝执行</b>，而不是拿空文本去跑规则。
 *       空文本会让"必备条款"类规则报缺失——把"没解析成功"说成"合同缺条款"。</li>
 *   <li><b>时钟显式注入</b>（{@code LocalDate.now()} 在 Service 层取一次，
 *       传给规则），规则内部不得读时钟，这样规则才可复现。</li>
 *   <li><b>重新校验先清旧结论</b>：规则集变化后保留上一轮结论会让报告混入
 *       已经不存在的判断。</li>
 * </ol>
 */
@Service
public class RuleCheckService {

    private static final Logger log = LoggerFactory.getLogger(RuleCheckService.class);

    private final ContractMapper contractMapper;
    private final ContractParsingService parsingService;
    private final DeterministicElementExtractor elementExtractor;
    private final RuleEngine ruleEngine;
    private final RuleFindingMapper findingMapper;

    public RuleCheckService(ContractMapper contractMapper,
                            ContractParsingService parsingService,
                            DeterministicElementExtractor elementExtractor,
                            RuleEngine ruleEngine,
                            RuleFindingMapper findingMapper) {
        this.contractMapper = contractMapper;
        this.parsingService = parsingService;
        this.elementExtractor = elementExtractor;
        this.ruleEngine = ruleEngine;
        this.findingMapper = findingMapper;
    }

    /**
     * 对一份合同执行规则校验。
     *
     * @throws com.demo.contract.parse.ContractException 合同不存在、或尚未解析成功时
     */
    @Transactional
    public CheckSummary check(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();

        Contract contract = contractMapper.findByIdAndTenant(contractId, tenantId);
        if (contract == null) {
            throw new com.demo.contract.parse.ContractException(
                    com.demo.contract.parse.ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }

        // 未解析成功就拒绝执行——这是刻意的严格：
        // 拿空文本跑规则会把"没解析"伪装成"合同缺条款"
        ContractText text = parsingService.requireText(contractId);
        String normalizedText = text.getText();

        var extraction = elementExtractor.extract(normalizedText);

        // 时钟只在这里读一次，然后注入规则；规则内部不得再读
        RuleContext context = new RuleContext(
                extraction.lookup(), normalizedText, LocalDate.now());

        RuleEngine.RuleExecutionResult result = ruleEngine.execute(context);

        // 先清旧结论再写新结论
        int removed = findingMapper.deleteByContract(contractId, tenantId);
        for (var finding : result.findings()) {
            findingMapper.insert(RuleFindingEntity.from(tenantId, contractId, finding));
        }

        // 状态推进：PARSED -> RULE_CHECKED（已是该状态则跳过，保持幂等）
        if (contract.getStatus() == ContractStatus.PARSED) {
            contractMapper.updateStatus(contractId, tenantId, ContractStatus.RULE_CHECKED);
        }

        CheckSummary summary = new CheckSummary(
                contractId,
                result.total(),
                (int) result.hitCount(),
                (int) result.passCount(),
                (int) result.undeterminedCount(),
                result.errorCount(),
                result.hasRuleErrors(),
                extraction.summary(),
                removed);

        log.info("规则校验完成: contractId={} 共{}条 命中{} 通过{} 无法判定{} 出错{} 抽出要素{}个",
                contractId, summary.total(), summary.hits(), summary.passes(),
                summary.undetermined(), summary.ruleErrors(), extraction.extractedCount());

        if (result.hasRuleErrors()) {
            log.warn("本次校验存在规则自身出错: contractId={} count={}", contractId, result.errorCount());
        }
        if (summary.undetermined() > 0) {
            // 无法判定的条数是本系统最重要的健康指标：偏高说明抽取质量差
            log.info("存在无法判定的结论，需人工介入: contractId={} count={}",
                    contractId, summary.undetermined());
        }

        return summary;
    }

    /** 读取已落库的结论。 */
    @Transactional(readOnly = true)
    public List<RuleFindingEntity> findings(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return findingMapper.findByContract(contractId, tenantId);
    }

    /** 只读命中的结论。 */
    @Transactional(readOnly = true)
    public List<RuleFindingEntity> hits(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return findingMapper.findHits(contractId, tenantId);
    }

    /**
     * 一次校验的汇总。
     *
     * @param hits         命中条数（合同确实有问题）
     * @param passes       通过条数（确定没问题）
     * @param undetermined <b>无法判定条数</b>——信息不足，需人工。
     *                     这个数字偏高意味着抽取质量差或规则依赖的字段没产出，
     *                     应当在界面上显著提示，而不是埋在列表里
     * @param ruleErrors   规则自身出错的条数，需要告警而不是当正常结果
     */
    public record CheckSummary(
            Long contractId,
            int total,
            int hits,
            int passes,
            int undetermined,
            int ruleErrors,
            boolean hasRuleErrors,
            Map<String, String> extractedElements,
            int removedPreviousFindings
    ) {
        /** 是否存在"需要人看一眼"的结论（命中或无法判定）。 */
        public boolean needsHumanAttention() {
            return hits > 0 || undetermined > 0 || hasRuleErrors;
        }

        public RuleResult overallHealth() {
            if (hasRuleErrors) {
                return RuleResult.UNDETERMINED;
            }
            return undetermined > 0 ? RuleResult.UNDETERMINED : RuleResult.PASS;
        }
    }
}
