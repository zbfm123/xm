package com.demo.contract.parse.text;

import org.springframework.stereotype.Component;

import static com.demo.contract.parse.text.NormalizationPhases.COLLAPSE_WHITESPACE;
import static com.demo.contract.parse.text.NormalizationPhases.FULLWIDTH_TO_HALFWIDTH;
import static com.demo.contract.parse.text.NormalizationPhases.UNIFY_NEWLINES;
import static com.demo.contract.parse.text.NormalizationPhases.removeRepeatedLines;
import static com.demo.contract.parse.text.NormalizationPhases.run;

/**
 * 文本归一化：把提取出的原文变成统一形式的文本，并保留回查原文的坐标映射。
 *
 * <p>步骤顺序是有讲究的，不能随意调换：
 * <ol>
 *   <li><b>统一换行</b>——后续按行处理的前提</li>
 *   <li><b>全角转半角</b>——先转换，空白折叠才能识别全角空格 {@code U+3000}</li>
 *   <li><b>去页眉页脚</b>——此时行结构已规整，统计重复行才准确</li>
 *   <li><b>折叠空白</b>——最后统一收尾</li>
 * </ol>
 *
 * <p>最重要的一条纪律：<b>任何一步都必须同步维护映射</b>。
 * 归一化一旦丢失坐标，下游"AI 结论要能定位到原文"就无法实现（不变式 I-02），
 * 而且症状是"区间整体偏移"——文本看起来完全正常，不写测试根本发现不了。
 */
@Component
public class TextNormalizer {

    /** 触发页眉页脚清理所需的最少页数。单页文档没有重复页眉可言。 */
    private static final int MIN_PAGES_FOR_HEADER_REMOVAL = 3;

    /**
     * 视为页眉/页脚的行长度上限（字符，<b>严格小于</b>该值才会被判定）。
     *
     * <p>取 30 是刻意保守的：
     * <ul>
     *   <li>真实页眉页脚（公司名、文档标题、页码）通常在 30 字以内</li>
     *   <li>超过 30 字的重复行更可能是合同里的<b>标准条款</b>，删掉它会造成实质信息损失</li>
     * </ul>
     *
     * <p>这个阈值被调整过一次：最初设 40 且用 {@code <=}，
     * 结果一句正好 40 字的重复条款被当成页眉删掉了。
     * <b>误删正文的代价远高于留下页眉</b>——留下的页眉人一眼能忽略，
     * 删掉的正文没人能找回来。
     */
    private static final int MAX_HEADER_LINE_LENGTH = 30;

    /**
     * 归一化。
     *
     * @param rawText   提取器产出的原文
     * @param pageCount 文档页数，用于决定是否清理页眉页脚；{@code <= 1} 表示不分页或单页
     */
    public NormalizedText normalize(String rawText, int pageCount) {
        if (rawText == null) {
            throw new IllegalArgumentException("待归一化文本不能为 null");
        }

        int[] identity = new int[rawText.length()];
        for (int i = 0; i < identity.length; i++) {
            identity[i] = i;
        }

        var step = run(rawText, identity, UNIFY_NEWLINES);
        step = run(step.text(), step.toOrigin(), FULLWIDTH_TO_HALFWIDTH);
        step = run(step.text(), step.toOrigin(),
                removeRepeatedLines(pageCount, MIN_PAGES_FOR_HEADER_REMOVAL, MAX_HEADER_LINE_LENGTH));
        step = run(step.text(), step.toOrigin(), COLLAPSE_WHITESPACE);

        NormalizedText result = new NormalizedText(step.text(), step.toOrigin(), rawText);
        // 构建后立刻自检：映射错位在写入数据库之后再发现，排查成本高得多
        result.assertConsistent();
        return result;
    }

    /** 单页/不分页文档的便捷入口。 */
    public NormalizedText normalize(String rawText) {
        return normalize(rawText, 1);
    }
}
