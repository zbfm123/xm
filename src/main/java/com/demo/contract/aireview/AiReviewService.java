package com.demo.contract.aireview;

import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiClient;
import com.demo.contract.aireview.client.AiErrorCode;
import com.demo.contract.aireview.domain.AiFindingRow;
import com.demo.contract.aireview.mapper.AiFindingMapper;
import com.demo.contract.aireview.support.FindingSchemaValidator;
import com.demo.contract.aireview.support.PromptTemplates;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.extract.evidence.AlignmentFailure;
import com.demo.contract.extract.evidence.AlignmentResult;
import com.demo.contract.extract.evidence.EvidenceAligner;
import com.demo.contract.parse.ContractException;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ParseErrorCode;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.mapper.ContractMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 风险审查：T-016，本项目"给概率性输出加工程约束"的完整落点。
 *
 * <p>四道闸门，逐层收紧：
 * <pre>
 *   1. 提示词固定 schema（含 riskType 枚举）
 *   2. 响应做 schema 校验 → 不合法**整体丢弃**，不部分采纳、不填默认值
 *   3. 证据对齐 → 引文必须在原文定位到，否则标 EVIDENCE_MISMATCH 转人工
 *   4. 置信度裁决 → 低置信度强制升级复核；**所有条目的初始状态都不是"已生效"**
 * </pre>
 *
 * <p>核心思路不是让模型更准，而是<b>让它的错误变得可检测</b>。
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    /** riskType 固定枚举。模型给出枚举外的值 → schema 非法。 */
    private static final Set<String> ALLOWED_RISK_TYPES = new LinkedHashSet<>(List.of(
            "UNLIMITED_LIABILITY", "UNILATERAL_TERMINATION", "AUTO_RENEWAL",
            "VAGUE_PAYMENT", "UNCAPPED_PENALTY", "CONFIDENTIALITY_GAP",
            "JURISDICTION_UNCLEAR", "UNKNOWN_RISK"));

    /**
     * 需强制主管复核的阈值下限。
     *
     * <p>低于此值标 {@code LOW_CONFIDENCE}：**不进报告正文**，必须人工处理。
     */
    private final double lowConfidenceThreshold;

    private final AiClient aiClient;
    private final PromptTemplates prompts;
    private final FindingSchemaValidator validator;
    private final EvidenceAligner aligner;
    private final ObjectMapper objectMapper;
    private final ContractMapper contractMapper;
    private final ContractParsingService parsingService;
    private final AiFindingMapper findingMapper;
    private final com.demo.contract.aireview.client.AiCostGuard costGuard;
    /**
     * AI 结果缓存。
     *
     * <p>⚠️ 它在 2026-10-07 之前**只被用来清理、从未被读写**——
     * 于是"缓存"这个说法一直是假的：每次审查都真的调 AI。
     * 见 {@code review()} 里的说明。
     */
    private final AiResultCache aiResultCache;
    private final String modelVersion;

    public AiReviewService(AiClient aiClient,
                           PromptTemplates prompts,
                           FindingSchemaValidator validator,
                           EvidenceAligner aligner,
                           ObjectMapper objectMapper,
                           ContractMapper contractMapper,
                           ContractParsingService parsingService,
                           AiFindingMapper findingMapper,
                           com.demo.contract.aireview.client.AiCostGuard costGuard,
                           AiResultCache aiResultCache,
                           @Value("${app.workflow.low-confidence-threshold}") double lowConfidenceThreshold,
                           @Value("${app.ai.model}") String modelVersion) {
        this.aiClient = aiClient;
        this.prompts = prompts;
        this.validator = validator;
        this.aligner = aligner;
        this.objectMapper = objectMapper;
        this.contractMapper = contractMapper;
        this.parsingService = parsingService;
        this.findingMapper = findingMapper;
        this.costGuard = costGuard;
        this.aiResultCache = aiResultCache;
        this.lowConfidenceThreshold = lowConfidenceThreshold;
        this.modelVersion = modelVersion;
    }

    /**
     * 执行 AI 风险审查。
     *
     * <p>产出的每一条都是<b>候选</b>：状态为 {@code PENDING} / {@code LOW_CONFIDENCE} /
     * {@code EVIDENCE_MISMATCH} / {@code EVIDENCE_AMBIGUOUS}，
     * <b>没有"已生效"这个状态</b>——采信权的归属是人工（决策 D-02）。
     */
    @Transactional
    public ReviewSummary review(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();

        Contract contract = contractMapper.findByIdAndTenant(contractId, tenantId);
        if (contract == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }
        var text = parsingService.requireText(contractId);

        costGuard.beginContract();

        // ==============================================================
        // ⚠️ 先查缓存，命中就**不调 AI**（这是缓存存在的唯一理由：省钱）
        // ==============================================================
        //
        // 【补这段的原因】在它之前，AiResultCache 是一个**只有删除、没有读写**的组件：
        //   · put() 全项目无人调用 -> 缓存从来没被写入过
        //   · get() 全项目无人调用 -> 因此永远不可能命中
        //   · 唯一的生产调用是 ContractService.delete() 里的 evictByTextHash
        // 结果是：配置写着 cache-enabled: true、缓存类写得挺完整、
        // 删合同时还会"清理缓存"，**但每次审查依然真的调 AI**——
        // 一个看起来在工作、实际一次都没生效的缓存。
        //
        // 这类"管线接好了但没水流过"的问题，从单元测试里看不出来
        // （缓存的 put/get 都测过，就是没人调用它们）。
        String rawResponse = aiResultCache.get(AiResultCache.Operation.REVIEW, text.getTextHash());
        if (rawResponse != null) {
            log.info("AI 审查命中缓存，跳过调用（省一次调用）: contractId={} textHash={}",
                    contractId, text.getTextHash());
        } else {
            rawResponse = aiClient.complete(
                    prompts.riskReviewSystemPrompt(), text.getText());
            // ⚠️ 缓存的是**模型原始响应**，不是解析后的结论。
            //    理由：结论里带着原文对齐结果，而对齐依赖解析管线；
            //    缓存原始响应意味着"换个对齐实现也不用清缓存"，
            //    而且与 AiResultCache 的键设计（textHash+提示词版本+模型）语义一致。
            aiResultCache.put(AiResultCache.Operation.REVIEW, text.getTextHash(), rawResponse);
        }

        JsonNode root = parseJson(rawResponse);
        JsonNode findings = root.path("findings");
        if (findings.isMissingNode() || findings.isNull()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "响应缺少 findings 字段");
        }
        if (!findings.isArray()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                    "findings 不是数组，实际类型: " + findings.getNodeType());
        }

        var map = parsingService.requireAlignmentMap(contractId);

        List<AiFindingRow> rows = new ArrayList<>();
        int pending = 0;
        int lowConfidence = 0;
        int mismatch = 0;

        for (JsonNode item : findings) {
            validator.requireObject(item, "findings[]");

            String riskType = validator.requireText(item, "riskType");
            if (!ALLOWED_RISK_TYPES.contains(riskType)) {
                // 枚举越界 → 整体判 schema 非法，而不是"当作 UNKNOWN_RISK"。
                // 静默归类会掩盖"模型没遵守约定"这个信号。
                throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                        "riskType 不在约定枚举内: " + riskType);
            }

            String quote = validator.requireText(item, "quote");
            double selfScore = validator.requireConfidence(item);

            AlignmentResult alignment = aligner.align(quote, map);

            AiFindingRow row = new AiFindingRow();
            row.setTenantId(tenantId);
            row.setContractId(contractId);
            row.setRiskType(riskType);
            row.setQuote(truncate(quote, 2000));
            row.setModelVersion(modelVersion);
            row.setPromptVersion(PromptTemplates.PROMPT_VERSION);

            if (!alignment.matched()) {
                mismatch++;
                // 未命中：不写区间、置信度记 0，状态明确表达失败原因。
                // 这一条**不进报告正文**，只作为人工待办。
                row.setCharStart(null);
                row.setCharEnd(null);
                row.setMatchLevel(null);
                row.setConfidence(BigDecimal.ZERO);
                row.setStatus(failureStatus(alignment.failure()));
                row.setStatusReason(describeFailure(alignment.failure(), quote));
            } else {
                row.setCharStart(alignment.start());
                row.setCharEnd(alignment.end());
                row.setMatchLevel(alignment.level().name());

                BigDecimal confidence = validator.computeConfidence(
                        selfScore, alignment.level(), quote);
                row.setConfidence(confidence);
                row.setStatusReason(null);

                if (confidence.doubleValue() < lowConfidenceThreshold) {
                    lowConfidence++;
                    row.setStatus("LOW_CONFIDENCE");
                    row.setStatusReason(String.format(
                            "置信度 %s 低于阈值 %.2f，需人工（主管）复核后才能进入报告",
                            confidence.toPlainString(), lowConfidenceThreshold));
                } else {
                    pending++;
                    row.setStatus("PENDING");
                }
            }
            rows.add(row);
        }

        int removed = findingMapper.deleteByContract(contractId, tenantId);
        for (AiFindingRow row : rows) {
            findingMapper.insert(row);
        }

        ReviewSummary summary = new ReviewSummary(
                contractId, rows.size(), pending, lowConfidence, mismatch, removed,
                aiClient.providerName(), modelVersion, PromptTemplates.PROMPT_VERSION);

        log.info("AI 审查完成: contractId={} 共{}条 待复核{} 低置信度{} 证据不匹配{} provider={}",
                contractId, summary.total(), pending, lowConfidence, mismatch, summary.provider());

        if (mismatch > 0) {
            log.warn("有 {} 条 AI 结论的引文无法在原文定位，已转人工且不进报告正文 "
                            + "（不变式 I-02）: contractId={}", mismatch, contractId);
        }

        return summary;
    }

    /** 读取全部候选结论。 */
    @Transactional(readOnly = true)
    public List<AiFindingRow> findings(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return findingMapper.findByContract(contractId, tenantId);
    }

    /**
     * 只取可进入报告正文的结论。
     *
     * <p>"可进入"= 证据已定位。**对齐失败的条目在这里被过滤掉**，
     * 这是不变式 I-02 在读取侧的落点：即使有人写了个新接口忘了过滤，
     * 也应该走这个方法。
     */
    @Transactional(readOnly = true)
    public List<AiFindingRow> reportableFindings(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return findingMapper.findReportable(contractId, tenantId);
    }

    private String failureStatus(AlignmentFailure failure) {
        return switch (failure) {
            case AMBIGUOUS -> "EVIDENCE_AMBIGUOUS";
            case NOT_FOUND, TOO_SHORT, INVALID_INPUT -> "EVIDENCE_MISMATCH";
        };
    }

    private String describeFailure(AlignmentFailure failure, String quote) {
        String base = switch (failure) {
            case NOT_FOUND -> "引文在原文中定位不到（模型改写或幻觉），该结论不进报告正文";
            case AMBIGUOUS -> "引文在原文中多处出现，无法确定具体位置";
            case TOO_SHORT -> "引文过短，不足以作为证据";
            case INVALID_INPUT -> "输入非法";
        };
        return base + "。原引文：" + truncate(quote, 150);
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "模型返回的不是合法 JSON", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * 审查汇总。
     *
     * @param pending       证据已定位、置信度达标，等待人工采信的条数
     * @param lowConfidence 置信度不达标的条数，**必须人工复核且不进报告正文**
     * @param mismatch      <b>引文无法定位的条数</b>——本模块最重要的健康指标。
     *                      它偏高说明模型在改写引文，或提示词需要调整
     */
    public record ReviewSummary(
            Long contractId,
            int total,
            int pending,
            int lowConfidence,
            int mismatch,
            int removedPrevious,
            String provider,
            String modelVersion,
            String promptVersion
    ) {
        /** 可进入报告正文的条数（证据已定位）。 */
        public int reportable() {
            return total - mismatch;
        }

        /** 引文定位成功率。 */
        public double alignmentRate() {
            return total == 0 ? 1.0 : (double) reportable() / total;
        }
    }
}
