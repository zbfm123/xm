package com.demo.contract.parse;

import com.demo.contract.auth.TenantContext;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.parse.domain.ContractText;
import com.demo.contract.parse.mapper.ContractMapper;
import com.demo.contract.parse.mapper.ContractTextMapper;
import com.demo.contract.parse.text.ContractTextExtractor;
import com.demo.contract.parse.text.ExtractionResult;
import com.demo.contract.parse.text.NormalizedText;
import com.demo.contract.parse.text.TextNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Map;

/**
 * 合同解析：文件 → 归一化文本 → 落库（T-009 / T-010）。
 *
 * <p>与 {@link ContractService} 分开的理由：上传与解析的失败模式完全不同。
 * 上传失败是格式与存储问题，解析失败是文档内容问题（加密、扫描件、损坏）。
 * 混在一个类里会让两边的事务边界都变难说清。
 *
 * <p>事务与 IO 的边界：<b>文件读取与解析在事务内进行</b>，因为解析结果必须与
 * 状态更新原子提交——否则会出现"状态是 PARSED 但没有正文"的不一致。
 * 当前解析都在本地完成（无网络调用），因此不会出现长事务占用连接的问题。
 * 将来若解析改为远程服务，必须改成"先解析、再开事务写入"。
 */
@Service
public class ContractParsingService {

    private static final Logger log = LoggerFactory.getLogger(ContractParsingService.class);

    private final ContractService contractService;
    private final ContractMapper contractMapper;
    private final ContractTextMapper contractTextMapper;
    private final ContractTextExtractor extractor;
    private final TextNormalizer normalizer;

    public ContractParsingService(ContractService contractService,
                                  ContractMapper contractMapper,
                                  ContractTextMapper contractTextMapper,
                                  ContractTextExtractor extractor,
                                  TextNormalizer normalizer) {
        this.contractService = contractService;
        this.contractMapper = contractMapper;
        this.contractTextMapper = contractTextMapper;
        this.extractor = extractor;
        this.normalizer = normalizer;
    }

    /**
     * 解析合同文本。
     *
     * <p>失败时的处理原则（T-010）：<b>状态置为 {@code PARSE_FAILED}，但不产生任何正文行</b>。
     * 半个状态（有 contract_text 但状态是失败）会让下游不确定该不该用这份文本。
     *
     * <p>另外：解析失败是<b>正常业务结果</b>，不是异常。用户上传了一份加密 PDF，
     * 系统正常工作并如实告知；抛出异常会让日志里混入大量"预期内的失败"。
     * 因此这里捕获 {@link ContractException} 并转成状态，只对真正的意外异常放行。
     *
     * @return 解析结果摘要（成功或失败原因）
     */
    @Transactional
    public ParseOutcome parse(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();

        Contract contract = contractMapper.findByIdAndTenant(contractId, tenantId);
        if (contract == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }

        if (contract.getStatus() == ContractStatus.DELETED) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同已删除");
        }

        // 重复解析是幂等的：已有正文就直接返回，不重复提取
        ContractText existing = contractTextMapper.findByContractId(contractId, tenantId);
        if (existing != null && contract.getStatus() == ContractStatus.PARSED) {
            log.info("合同已解析，跳过: contractId={}", contractId);
            return ParseOutcome.alreadyParsed(contractId, existing.getTextHash(), existing.getText().length());
        }

        // 状态推进：UPLOADED -> PARSING
        ContractStatus previous = contract.getStatus();
        if (previous != ContractStatus.PARSING) {
            if (!previous.canMoveTo(ContractStatus.PARSING)) {
                throw new IllegalStateException(
                        "非法的状态迁移: " + previous + " -> PARSING (contractId=" + contractId + ")");
            }
            contractMapper.updateStatus(contractId, tenantId, ContractStatus.PARSING);
        }

        try (InputStream in = contractService.openFile(contractId)) {
            ExtractionResult extracted = extractor.extract(in, contract.getOriginalFilename());

            if (extracted.isEmpty()) {
                // 疑似扫描件：能打开、页数正常，但没有文本。
                // 必须显式失败，否则下游会以为"这份合同没有风险条款"。
                contractMapper.updateStatus(contractId, tenantId, ContractStatus.PARSE_FAILED);
                log.warn("合同无可提取文本，疑似扫描件: contractId={} 页数={}",
                        contractId, extracted.pageCount());
                return ParseOutcome.failed(contractId, ParseErrorCode.NO_EXTRACTABLE_TEXT,
                        "未提取到文本，疑似扫描件（本期不支持）");
            }

            NormalizedText normalized = normalizer.normalize(extracted.rawText(), extracted.pageCount());

            if (normalized.text().isBlank()) {
                // 归一化把内容全洗掉了：说明提取到的只是页眉页脚之类的噪声
                contractMapper.updateStatus(contractId, tenantId, ContractStatus.PARSE_FAILED);
                return ParseOutcome.failed(contractId, ParseErrorCode.NO_EXTRACTABLE_TEXT,
                        "归一化后无有效正文");
            }

            String textHash = Digests.sha256Hex(normalized.text());

            // 同一合同重复解析时覆盖旧正文，而不是插出第二行
            if (existing != null) {
                contractTextMapper.deleteByContractId(contractId, tenantId);
            }

            ContractText contractText = new ContractText();
            contractText.setTenantId(tenantId);
            contractText.setContractId(contractId);
            contractText.setText(normalized.text());
            contractText.setTextHash(textHash);
            // 偏移映射序列化为 "原下标1,原下标2,..."，紧凑且易于校验。
            // 用 JSON 会更通用，但这里只需要一个整数序列，JSON 解析开销不值得。
            contractText.setOffsetMap(serializeOffsets(normalized.offsetsToOriginal()));
            contractText.setNoExtractableText(false);
            contractTextMapper.insert(contractText);

            // 回填 textHash：AI 结果缓存以此为键（决策 D-20）
            contractMapper.updateTextHash(contractId, tenantId, textHash);
            contractMapper.updateStatus(contractId, tenantId, ContractStatus.PARSED);

            log.info("合同解析完成: contractId={} 页数={} 原文字符={} 归一化字符={} textHash={}",
                    contractId, extracted.pageCount(), extracted.rawLength(),
                    normalized.length(), textHash.substring(0, 12));

            return ParseOutcome.success(contractId, textHash, normalized.length(),
                    extracted.pageCount(), normalized.summary());

        } catch (ContractException e) {
            // 预期内的失败：记录状态后作为正常结果返回，不向上抛
            contractMapper.updateStatus(contractId, tenantId, ContractStatus.PARSE_FAILED);
            log.info("合同解析失败（预期内）: contractId={} code={} msg={}",
                    contractId, e.getCode(), e.getMessage());
            return ParseOutcome.failed(contractId, e.getCode(), e.getMessage());
        } catch (java.io.IOException e) {
            contractMapper.updateStatus(contractId, tenantId, ContractStatus.PARSE_FAILED);
            log.error("合同文件读取失败: contractId={}", contractId, e);
            return ParseOutcome.failed(contractId, ParseErrorCode.STORAGE_UNAVAILABLE, "文件读取失败");
        }
    }

    /**
     * 读取归一化文本，供后续抽取/审查使用。
     *
     * <p>没有正文时抛异常而不是返回 null：调用方拿到 null 之后
     * 很可能会"当作空文本继续处理"，那就退化成了"没解析成功也算通过"。
     */
    @Transactional(readOnly = true)
    public ContractText requireText(Long contractId) {
        Long tenantId = TenantContext.requireTenantId();
        ContractText text = contractTextMapper.findByContractId(contractId, tenantId);
        if (text == null) {
            throw new ContractException(ParseErrorCode.PARSE_FAILED,
                    "合同尚未解析成功，无可用正文");
        }
        return text;
    }

    /** 把偏移映射序列化成紧凑形式。 */
    private String serializeOffsets(int[] offsets) {
        StringBuilder sb = new StringBuilder(offsets.length * 4);
        for (int i = 0; i < offsets.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(offsets[i]);
        }
        return sb.toString();
    }

    /**
     * 解析结果。
     *
     * @param success  是否成功
     * @param errorCode 失败时的错误码，成功时为 null
     * @param textHash 成功时的文本哈希（AI 缓存键）
     */
    public record ParseOutcome(boolean success,
                               Long contractId,
                               String textHash,
                               int normalizedLength,
                               int pageCount,
                               ParseErrorCode errorCode,
                               String message,
                               Map<String, Object> stats) {

        static ParseOutcome success(Long contractId, String textHash, int normalizedLength,
                                    int pageCount, Map<String, Object> stats) {
            return new ParseOutcome(true, contractId, textHash, normalizedLength,
                    pageCount, null, "解析成功", stats);
        }

        static ParseOutcome failed(Long contractId, ParseErrorCode code, String message) {
            return new ParseOutcome(false, contractId, null, 0, 0, code, message, Map.of());
        }

        static ParseOutcome alreadyParsed(Long contractId, String textHash, int length) {
            return new ParseOutcome(true, contractId, textHash, length, 0, null,
                    "已解析，未重复处理", Map.of("idempotent", true));
        }
    }
}
