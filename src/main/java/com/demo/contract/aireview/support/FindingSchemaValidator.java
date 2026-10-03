package com.demo.contract.aireview.support;

import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiErrorCode;
import com.demo.contract.extract.evidence.MatchLevel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型响应的结构校验与置信度计算。
 *
 * <p>本类集中了"<b>把不可信输出变成可裁决数字</b>"这件事的两半：
 * 结构上必须合法，数值上必须反映证据强度。
 */
@Component
public class FindingSchemaValidator {

    /**
     * 引文长度低于该值时下调置信度。
     *
     * <p>理由与证据对齐的 {@code TOO_SHORT} 一致但更宽松：
     * 对齐阶段直接拒绝过短引文，这里处理的是"勉强够长但证据力弱"的情况。
     * 例如一句 8 个字的引文虽然能定位，但它覆盖的信息量很小，
     * 不该和高置信度等价。
     */
    private static final int SHORT_QUOTE_LENGTH = 12;

    /** 过短引文的置信度乘数。 */
    private static final double SHORT_QUOTE_PENALTY = 0.85;

    /**
     * 校验模型返回的 JSON 是否是可解析的对象。
     *
     * @throws AiCallException {@code SCHEMA_INVALID}——**整体丢弃，不做部分采纳**
     */
    public void requireObject(com.fasterxml.jackson.databind.JsonNode node, String what) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "响应缺少 " + what);
        }
        if (!node.isObject()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                    what + " 不是对象，实际类型: " + node.getNodeType());
        }
    }

    /** 取必填字符串字段，缺失即判 schema 非法。 */
    public String requireText(com.fasterxml.jackson.databind.JsonNode parent, String field) {
        var node = parent.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                    "缺少必填字段 " + field + "（不允许用默认值补齐）");
        }
        return node.asText().trim();
    }

    /** 取可选字符串字段，缺失返回 null（与必填字段区分开）。 */
    public String optionalText(com.fasterxml.jackson.databind.JsonNode parent, String field) {
        var node = parent.path(field);
        return (node.isMissingNode() || node.isNull() || node.asText().isBlank())
                ? null : node.asText().trim();
    }

    /**
     * 取置信度，越界或非法值时判 schema 非法。
     *
     * <p>不用"截断到 0~1"兜底：模型给出 {@code 1.7} 或 {@code "高"} 说明它没有遵守约定，
     * 静默修正会掩盖这个信号。
     */
    public double requireConfidence(com.fasterxml.jackson.databind.JsonNode parent) {
        var node = parent.path("confidence");
        if (node.isMissingNode() || node.isNull()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "缺少 confidence 字段");
        }
        double v = node.asDouble(-1);
        if (v < 0 || v > 1) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                    "confidence 越界: " + node.asText() + "（应在 0~1 之间）");
        }
        return v;
    }

    /**
     * 计算综合置信度。
     *
     * <p>公式：{@code 模型自评 × 匹配级别权重 × 引文长度惩罚}
     *
     * <p>三项各有理由：
     * <ul>
     *   <li><b>模型自评</b>——模型对自己的判断有多确信</li>
     *   <li><b>匹配级别权重</b>——证据有多硬（EXACT 1.0 / NORMALIZED 0.9 / FUZZY 0.7）</li>
     *   <li><b>长度惩罚</b>——防止"引四个字就说全文都在讲这个"</li>
     * </ul>
     *
     * <p>只用自评是不行的：模型普遍高估自己。把"证据强度"乘进去，
     * 才让这个数字真正反映"这条结论有多值得信"。
     */
    public BigDecimal computeConfidence(double modelSelfScore, MatchLevel level, String quote) {
        double value = modelSelfScore * level.weight();

        if (quote != null && quote.length() < SHORT_QUOTE_LENGTH) {
            value *= SHORT_QUOTE_PENALTY;
        }

        return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, value)))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
