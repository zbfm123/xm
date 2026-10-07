package com.demo.contract.extract;

import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiClient;
import com.demo.contract.aireview.client.AiErrorCode;
import com.demo.contract.aireview.support.FindingSchemaValidator;
import com.demo.contract.aireview.support.PromptTemplates;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.extract.domain.ContractElementRow;
import com.demo.contract.extract.evidence.AlignmentFailure;
import com.demo.contract.extract.evidence.AlignmentResult;
import com.demo.contract.extract.evidence.EvidenceAligner;
import com.demo.contract.extract.evidence.MatchLevel;
import com.demo.contract.extract.mapper.ContractElementMapper;
import com.demo.contract.parse.ContractException;
import com.demo.contract.aireview.AiResultCache;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ParseErrorCode;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractText;
import com.demo.contract.parse.mapper.ContractMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 要素抽取：调用模型 → schema 校验 → 证据对齐 → 置信度 → 落库。
 *
 * <p>本类是 T-015，也是"<b>AI 输出必须可核验</b>"这条主线的完整实现。
 * 每一步都在减少"不可信输入"的通过率：
 *
 * <pre>
 *   模型输出
 *     ├─ schema 非法 → 整体丢弃（SCHEMA_INVALID），不部分采纳、不填默认值
 *     └─ schema 合法
 *          ├─ 引文对齐失败 → 该字段标 UNKNOWN，**仍然落库**（能看出"抽过但失败"）
 *          └─ 对齐成功 → 置信度 = 模型自评 × 匹配级别权重 × 长度惩罚
 *                        → 达标 CONFIRMED，不达标 LOW_CONFIDENCE
 * </pre>
 *
 * <p>为什么对齐失败的字段也要落库：如果直接丢弃，
 * 数据库里"没有这一行"将同时表示"从没抽过"和"抽过但失败"，
 * 人工复核时无法区分这两种情况。
 */
@Service
public class ElementExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ElementExtractionService.class);

    /** 模型字段名 → 内部枚举名。映射显式写出，避免靠命名约定猜。 */
    private static final Map<String, String> FIELD_ALIASES = new LinkedHashMap<>() {{
        put("partyA", "PARTY_A");
        put("partyB", "PARTY_B");
        put("amount", "AMOUNT");
        put("amountInWords", "AMOUNT_IN_WORDS");
        put("signDate", "SIGN_DATE");
        put("effectiveDate", "EFFECTIVE_DATE");
        put("expiryDate", "EXPIRY_DATE");
        put("paymentTerm", "PAYMENT_TERM");
        put("disputeResolution", "DISPUTE_RESOLUTION");
    }};

    /** 低于该置信度标记为 LOW_CONFIDENCE，需人工确认。 */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.60;

    private final AiClient aiClient;
    private final PromptTemplates prompts;
    private final FindingSchemaValidator validator;
    private final EvidenceAligner aligner;
    private final ObjectMapper objectMapper;
    private final ContractMapper contractMapper;
    private final ContractParsingService parsingService;
    private final ContractElementMapper elementMapper;
    private final com.demo.contract.aireview.client.AiCostGuard costGuard;

    /**
     * AI 结果缓存。与 {@code AiReviewService} 共用同一份缓存与同一套键约定
     * （{@code textHash + 提示词版本 + 模型}），因此抽取与审查各自命中自己的条目。
     */
    private final com.demo.contract.aireview.AiResultCache aiResultCache;

    public ElementExtractionService(AiClient aiClient,
                                    PromptTemplates prompts,
                                    FindingSchemaValidator validator,
                                    EvidenceAligner aligner,
                                    ObjectMapper objectMapper,
                                    ContractMapper contractMapper,
                                    ContractParsingService parsingService,
                                    ContractElementMapper elementMapper,
                                    com.demo.contract.aireview.client.AiCostGuard costGuard,
                           com.demo.contract.aireview.AiResultCache aiResultCache) {
        this.aiClient = aiClient;
        this.prompts = prompts;
        this.validator = validator;
        this.aligner = aligner;
        this.objectMapper = objectMapper;
        this.contractMapper = contractMapper;
        this.parsingService = parsingService;
        this.elementMapper = elementMapper;
        this.costGuard = costGuard;
        this.aiResultCache = aiResultCache;
    }

    /**
     * 对一份合同执行要素抽取。
     *
     * <p>AI 不可用时抛 {@link AiCallException}（{@code AI_UNAVAILABLE}），
     * <b>由调用方决定降级</b>——本方法不吞掉它，因为"AI 不可用"是需要显式告知用户的状态，
     * 而不是一个可以静默忽略的失败。
     */
    @Transactional
    public ExtractionSummary extract(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();

        Contract contract = contractMapper.findByIdAndTenant(contractId, tenantId);
        if (contract == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }
        ContractText text = parsingService.requireText(contractId);

        // 每份合同开始时重置单合同调用计数
        costGuard.beginContract();

        // ==============================================================
        // ⚠️ 先查缓存，命中就不调 AI（与 AiReviewService.review() 同一套做法）
        // ==============================================================
        //
        // 【为什么这里也要补】{@code docs/02-architecture.md} 的模块图里写的是
        // "extract：按 textHash 查缓存，未命中则按章节切块送大模型"——
        // 但补之前，实现里**同样没有读缓存**，所以那句话对 extract 也是不成立的。
        // 换句话说：文档承诺了缓存，代码从来没有，只是没人去核对这句话。
        //
        // 缓存的是**模型原始响应**，不是解析后的要素行——
        // 理由与 review() 一致：解析与对齐依赖管线实现，缓存原始响应更稳。
        String rawResponse = aiResultCache.get(AiResultCache.Operation.EXTRACT, text.getTextHash());
        if (rawResponse != null) {
            log.info("要素抽取命中缓存，跳过调用（省一次调用）: contractId={} textHash={}",
                    contractId, text.getTextHash());
        } else {
            rawResponse = aiClient.complete(
                    prompts.elementExtractionSystemPrompt(), text.getText());
            aiResultCache.put(AiResultCache.Operation.EXTRACT, text.getTextHash(), rawResponse);
        }

        JsonNode root = parseJson(rawResponse);
        JsonNode elements = root.path("elements");
        validator.requireObject(elements, "elements");

        List<ContractElementRow> rows = new ArrayList<>();
        int aligned = 0;
        int mismatch = 0;

        var map = parsingService.requireAlignmentMap(contractId);

        var fields = elements.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String modelField = entry.getKey();
            String fieldKey = FIELD_ALIASES.get(modelField);
            if (fieldKey == null) {
                // 模型给了不认识的字段：忽略但记日志。
                // 不报错是因为它不影响已知字段的正确性；记录下来便于发现提示词漂移。
                log.debug("忽略未知要素字段: {}", modelField);
                continue;
            }
            JsonNode item = entry.getValue();
            validator.requireObject(item, "elements." + modelField);

            String value = validator.requireText(item, "value");
            String quote = validator.requireText(item, "quote");
            double selfScore = validator.requireConfidence(item);

            AlignmentResult alignment = aligner.align(quote, map);

            ContractElementRow row = new ContractElementRow();
            row.setTenantId(tenantId);
            row.setContractId(contractId);
            row.setFieldKey(fieldKey);
            row.setQuote(truncate(quote, 1000));
            row.setSource("LLM");

            if (alignment.matched()) {
                aligned++;
                row.setElementValue(truncate(value, 500));
                row.setCharStart(alignment.start());
                row.setCharEnd(alignment.end());
                row.setMatchLevel(alignment.level().name());
                BigDecimal confidence = validator.computeConfidence(
                        selfScore, alignment.level(), quote);
                row.setConfidence(confidence);
                if (confidence.doubleValue() < LOW_CONFIDENCE_THRESHOLD) {
                    row.setStatus("LOW_CONFIDENCE");
                    row.setStatusReason("置信度 " + confidence.toPlainString()
                            + " 低于阈值 " + LOW_CONFIDENCE_THRESHOLD + "，需人工确认");
                } else {
                    row.setStatus("CONFIRMED");
                    row.setStatusReason(null);
                }
            } else {
                mismatch++;
                // 对齐失败：**不写入 value**，只留 UNKNOWN 与原因。
                // 写入 value 会让下游拿到一个没有证据支撑的值。
                row.setElementValue(null);
                row.setCharStart(null);
                row.setCharEnd(null);
                row.setMatchLevel(null);
                row.setConfidence(BigDecimal.ZERO);
                row.setStatus("UNKNOWN");
                row.setStatusReason(describeFailure(alignment.failure(), quote));
            }
            rows.add(row);
        }

        // 先清旧行再写新行：要素可重算，不需要留痕
        int removed = elementMapper.deleteByContract(contractId, tenantId);
        for (ContractElementRow row : rows) {
            elementMapper.insert(row);
        }

        ExtractionSummary summary = new ExtractionSummary(
                contractId, rows.size(), aligned, mismatch, removed, aiClient.providerName());

        log.info("要素抽取完成: contractId={} 共{}个字段 对齐成功{} 失败{} 旧行清除{} provider={}",
                contractId, summary.total(), aligned, mismatch, removed, summary.provider());

        if (mismatch > 0) {
            log.warn("存在引文无法定位的要素，已标 UNKNOWN 转人工: contractId={} count={}",
                    contractId, mismatch);
        }

        return summary;
    }

    /** 读取已落库的要素。 */
    @Transactional(readOnly = true)
    public List<ContractElementRow> elements(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        return elementMapper.findByContract(contractId, tenantId);
    }

    /**
     * 构造规则模块能用的要素视图。
     *
     * <p><b>这是 rule 与 extract 两个模块的唯一交界</b>：
     * 规则不直接依赖 {@code contract_element} 表结构，
     * 而是拿到一个 {@code ElementLookup}。这样两个模块可以独立演进。
     */
    @Transactional(readOnly = true)
    public com.demo.contract.rule.domain.ElementLookup asElementLookup(Long contractId) {
        List<ContractElementRow> rows = elements(contractId);

        var builder = com.demo.contract.rule.engine.MapElementLookup.builder();
        for (ContractElementRow row : rows) {
            var field = parseField(row.getFieldKey());
            if (field == null) {
                continue;
            }
            var status = parseStatus(row.getStatus());
            if (status == com.demo.contract.rule.domain.ElementStatus.UNKNOWN
                    || status == com.demo.contract.rule.domain.ElementStatus.CONFLICT) {
                builder.unknown(field);
                continue;
            }
            if (row.getElementValue() == null) {
                builder.unknown(field);
                continue;
            }
            putTyped(builder, field, row, status);
            if (row.getCharStart() != null && row.getCharEnd() != null) {
                builder.range(field, row.getCharStart(), row.getCharEnd());
            }
        }
        builder.defaultUnknown(com.demo.contract.rule.domain.ElementField.values());
        return builder;
    }

    /** 按字段类型放入对应类型的值——金额与日期必须转成 BigDecimal / LocalDate 才能被规则使用。 */
    private void putTyped(com.demo.contract.rule.engine.MapElementLookup builder,
                          com.demo.contract.rule.domain.ElementField field,
                          ContractElementRow row,
                          com.demo.contract.rule.domain.ElementStatus status) {
        // ⚠️ 局部变量不能叫 value。ContractElementRow 的属性改名为 elementValue 是有原因的：
        // 这个名字在 switch 的分支里、以及与其他枚举成员同处一个作用域时都会引起混淆。
        String raw = row.getElementValue();
        try {
            switch (field) {
                case AMOUNT -> builder.amount(field, new BigDecimal(raw.replace(",", "").trim()), status);
                case SIGN_DATE, EFFECTIVE_DATE, EXPIRY_DATE ->
                        builder.date(field, java.time.LocalDate.parse(raw.trim()), status);
                default -> builder.text(field, raw, status);
            }
        } catch (RuntimeException e) {
            // 值存在但格式无法转换成规则需要的类型：标为冲突而不是猜一个值。
            // 例如模型给出 "2026年1月" 这种不完整日期。
            log.debug("要素值无法转换为规则所需类型，标为冲突: field={} raw={}", field, raw);
            builder.conflict(field);
        }
    }

    private com.demo.contract.rule.domain.ElementField parseField(String key) {
        try {
            return com.demo.contract.rule.domain.ElementField.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private com.demo.contract.rule.domain.ElementStatus parseStatus(String status) {
        try {
            return com.demo.contract.rule.domain.ElementStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            return com.demo.contract.rule.domain.ElementStatus.UNKNOWN;
        }
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                    "模型返回的不是合法 JSON", e);
        }
    }

    private String describeFailure(AlignmentFailure failure, String quote) {
        String base = switch (failure) {
            case NOT_FOUND -> "引文在原文中定位不到（可能是模型改写或幻觉）";
            case AMBIGUOUS -> "引文在原文中多处出现，无法确定位置";
            case TOO_SHORT -> "引文过短，不足以作为证据";
            case INVALID_INPUT -> "输入非法";
        };
        return base + "，已转人工。引文：" + truncate(quote, 120);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * 抽取汇总。
     *
     * @param aligned  引文成功定位的字段数
     * @param mismatch <b>引文无法定位的字段数</b>——这个数字偏高说明模型在改写引文，
     *                 或者提示词需要调整。它是本模块最重要的健康指标
     */
    public record ExtractionSummary(
            Long contractId,
            int total,
            int aligned,
            int mismatch,
            int removedPrevious,
            String provider
    ) {
        /** 引文定位成功率。低于 1 说明有结论被降级。 */
        public double alignmentRate() {
            return total == 0 ? 0.0 : (double) aligned / total;
        }
    }
}
