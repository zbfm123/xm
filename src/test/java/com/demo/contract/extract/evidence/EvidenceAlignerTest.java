package com.demo.contract.extract.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据对齐测试（T-013，本项目核心）。
 *
 * <p>这个类要证明的核心命题是：
 * <b>模型给出的引文必须能被机器校验地定位回原文；定位不上就明确失败，绝不猜。</b>
 *
 * <p>因此测试的重点不是"能匹配上"，而是三件事：
 * <ol>
 *   <li><b>归一化匹配后坐标是原文坐标</b>，不是归一化文本坐标</li>
 *   <li><b>未命中时不返回任何位置</b>——给一个"差不多"的区间比不给更糟</li>
 *   <li><b>多处命中与过短引文都要显式标记</b>，交给调用方决定降级</li>
 * </ol>
 */
class EvidenceAlignerTest {

    private final EvidenceAligner aligner = new EvidenceAligner();

    /**
     * 构造一个"归一化文本 = 原文"的视图（恒等映射）。
     *
     * <p>用于不涉及归一化差异的用例：这样断言可以简单地把区间直接对回原字符串。
     */
    private static EvidenceAligner.OriginalMap identity(String text) {
        int[] offsets = new int[text.length()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = i;
        }
        return new EvidenceAligner.OriginalMap(text, offsets, text);
    }

    @Test
    @DisplayName("OriginalMap 校验映射长度，避免带着错位的映射进入下游")
    void originalMapShouldValidateMappingLength() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new EvidenceAligner.OriginalMap("abc", new int[]{0, 1}, "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("映射长度必须与归一化文本一致");
    }

    // ==================================================================
    @Nested
    @DisplayName("L1 精确匹配")
    class ExactMatching {

        @Test
        @DisplayName("引文与原文逐字相同 → EXACT，区间准确")
        void exactMatchShouldReturnCorrectRange() {
            String original = "甲方应在收到货物后三十日内支付全部价款。乙方应按时交付。";
            String quote = "收到货物后三十日内支付全部价款";

            AlignmentResult r = aligner.align(quote, identity(original));

            assertThat(r.matched()).isTrue();
            assertThat(r.level()).isEqualTo(MatchLevel.EXACT);
            assertThat(r.similarity()).isEqualTo(1.0);
            assertThat(original.substring(r.start(), r.end())).isEqualTo(quote);
            assertThat(r.ambiguous()).isFalse();
        }

        @Test
        @DisplayName("引文位于文本开头与结尾时区间都正确")
        void boundariesShouldBeCorrect() {
            // 引文长度需 >= 阈值（6），否则会被判 TOO_SHORT——那个用例在 MustNotGuess 里
            String original = "开头部分内容ABC，结尾部分内容XYZ。";
            String headQuote = "开头部分内容";
            AlignmentResult head = aligner.align(headQuote, identity(original));
            assertThat(head.matched()).isTrue();
            assertThat(head.start()).isZero();

            String tailQuote = "结尾部分内容XYZ";
            AlignmentResult tail = aligner.align(tailQuote, identity(original));
            assertThat(tail.matched()).isTrue();
            assertThat(original.substring(tail.start(), tail.end())).isEqualTo(tailQuote);
            assertThat(tail.end()).isLessThanOrEqualTo(original.length());
        }

        @Test
        @DisplayName("matchedText 回填原文中实际命中的片段")
        void matchedTextShouldBeFilled() {
            String original = "违约责任按日万分之五计算。";
            String quote = "按日万分之五";
            AlignmentResult r = aligner.align(quote, identity(original));
            assertThat(r.matchedText()).isEqualTo(quote);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L2 归一化匹配（含坐标回映射）")
    class NormalizedMatching {

        @Test
        @DisplayName("引文用半角、原文用全角 → 命中，且区间指向原文的全角字符")
        void halfWidthQuoteShouldMatchFullWidthOriginal() {
            // 原文用全角数字，模型输出的引文用了半角（很常见的改写）
            String original = "合同金额为１２８０００元整，乙方应于三十日内支付。";
            EvidenceAligner.OriginalMap map = normalizeMap(original);
            String quote = "128000元整";

            AlignmentResult r = aligner.align(quote, map);

            assertThat(r.matched())
                    .withFailMessage("全角/半角差异应当能被覆盖")
                    .isTrue();

            // 关键：区间必须落在原文坐标上，取出的原文包含全角数字
            String fromOriginal = original.substring(r.start(), r.end());
            assertThat(fromOriginal)
                    .withFailMessage("区间没有回映射到原文坐标，取到的是归一化文本坐标：%s", fromOriginal)
                    .contains("元整")
                    .contains("１２８０００");
            assertThat(r.similarity()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("分级依据是'归一化是否被用到'，而不是'引文字符串有没有变'")
        void levelShouldReflectWhetherNormalizationWasNeeded() {
            // 原文与引文都是半角、无多余空白 → 匹配不需要归一化 → EXACT
            String plain = "合同金额为128000元整，乙方应于三十日内支付。";
            AlignmentResult plainResult = aligner.align("128000元整，乙方", normalizeMap(plain));
            assertThat(plainResult.matched()).isTrue();
            assertThat(plainResult.level())
                    .withFailMessage("原文与引文都没有归一化差异，不该被判成 NORMALIZED")
                    .isEqualTo(MatchLevel.EXACT);

            // 原文用全角数字，归一化后变成半角 → 匹配确实用到了归一化 → NORMALIZED
            String fullWidth = "合同金额为１２８０００元整，乙方应于三十日内支付。";
            AlignmentResult fullWidthResult =
                    aligner.align("128000元整，乙方", normalizeMap(fullWidth));
            assertThat(fullWidthResult.matched()).isTrue();
            assertThat(fullWidthResult.level())
                    .withFailMessage("原文是全角、引文是半角，必须依赖归一化才能匹配")
                    .isEqualTo(MatchLevel.NORMALIZED);
        }

        @Test
        @DisplayName("原文含多余空白，引文是紧凑写法 → 归一化匹配后区间仍覆盖原文")
        void whitespaceDifferenceShouldBeNormalized() {
            String original = "第二条    付款方式：\n分两期支付，首期百分之五十。";
            EvidenceAligner.OriginalMap map = normalizeMap(original);
            // 引文里是单个空格、没有换行，且长度超过阈值
            String quote = "付款方式： 分两期支付，首期百分之五十。";

            AlignmentResult r = aligner.align(quote, map);

            assertThat(r.matched())
                    .withFailMessage("空白差异应当被覆盖，failure=%s", r.failure())
                    .isTrue();
            assertThat(r.level()).isEqualTo(MatchLevel.NORMALIZED);
            String fromOriginal = original.substring(r.start(), r.end());
            assertThat(fromOriginal).contains("付款方式").contains("分两期支付");
        }

        @Test
        @DisplayName("归一化删除了字符时，回映射仍能覆盖原文中被删除的空白")
        void mappingMustCoverRemovedCharacters() {
            // 原文里"甲"与"方"之间有一个全角空格，归一化后它变成一个半角空格
            String original = "甲　方：某某公司";
            EvidenceAligner.OriginalMap map = normalizeMap(original);

            AlignmentResult r = aligner.align("甲 方：某某公司", map);

            assertThat(r.matched()).isTrue();
            // 区间起点应是原文下标 0（"甲"），终点覆盖到"司"之后
            assertThat(r.start()).isZero();
            assertThat(original.substring(r.start(), r.end())).isEqualTo(original);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L3 模糊匹配")
    class FuzzyMatching {

        @Test
        @DisplayName("引文有个别字差异 → FUZZY 命中，相似度在阈值之上但小于 1")
        void slightDifferenceShouldMatchFuzzy() {
            String original = "乙方应当在收到货物之日起三十日内支付全部合同价款。";
            String quote = "乙方应当在收到货物之日起三十日内支付全部合同货款。";  // 价款→货款

            AlignmentResult r = aligner.align(quote, identity(original));

            assertThat(r.matched())
                    .withFailMessage("差一个字的引文应当能模糊命中，level=%s", r.level())
                    .isTrue();
            assertThat(r.level()).isEqualTo(MatchLevel.FUZZY);
            // 实测差 1 字约 0.96；断言区间而非精确值，避免阈值微调就breaking
            assertThat(r.similarity()).isGreaterThan(0.80).isLessThan(1.0);
            // 命中处的原文片段应当是高相似但不相同的那句
            assertThat(r.matchedText()).contains("价款");
        }

        @Test
        @DisplayName("相似度必须有区分度：完全不同的句子不能得到高分")
        void similarityMustDiscriminate() {
            String original = "乙方应当在收到货物之日起三十日内支付全部合同价款。";
            String unrelated = "甲方有权单方面解除本合同且不承担任何责任，乙方不得异议。";

            // 若相似度算法丢失位置/频次信息，这两句会被判成高度相似（实测过 1.0）
            AlignmentResult r = aligner.align(unrelated, identity(original));
            assertThat(r.matched())
                    .withFailMessage("完全不同的句子被模糊匹配命中，相似度算法失去区分度")
                    .isFalse();
        }

        @Test
        @DisplayName("FUZZY 的置信度权重必须低于 NORMALIZED —— 否则分级没有意义")
        void fuzzyWeightMustBeLowerThanNormalized() {
            assertThat(MatchLevel.FUZZY.weight()).isLessThan(MatchLevel.NORMALIZED.weight());
            assertThat(MatchLevel.NORMALIZED.weight()).isLessThan(MatchLevel.EXACT.weight());
        }

        @Test
        @DisplayName("差异过大时不 FUZZY 命中，宁可不给位置")
        void largeDifferenceShouldNotMatch() {
            String original = "乙方应当在收到货物之日起三十日内支付全部合同价款。";
            String quote = "甲方有权单方面解除本合同且不承担任何责任，乙方不得异议。";

            AlignmentResult r = aligner.align(quote, identity(original));

            assertThat(r.matched())
                    .withFailMessage("差异过大的引文被模糊匹配成了命中，会给出错误的证据位置")
                    .isFalse();
            assertThat(r.failure()).isEqualTo(AlignmentFailure.NOT_FOUND);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("未命中：绝不返回近似位置")
    class MustNotGuess {

        @Test
        @DisplayName("引文完全不存在 → 未命中，且不携带任何区间")
        void missingQuoteShouldFailWithoutLocation() {
            String original = "本合同项下的付款方式为分两期支付。";
            String quote = "乙方承担一切损失且责任无上限";

            AlignmentResult r = aligner.align(quote, identity(original));

            assertThat(r.matched()).isFalse();
            assertThat(r.start()).isNull();
            assertThat(r.end()).isNull();
            assertThat(r.failure()).isEqualTo(AlignmentFailure.NOT_FOUND);
            assertThat(r.level()).isEqualTo(MatchLevel.NONE);
        }

        @Test
        @DisplayName("多处命中且无法消歧 → AMBIGUOUS，并且不给位置")
        void ambiguousQuoteShouldFail() {
            // 同一句话出现两次
            String original = "乙方应于三十日内付款。其他条款若干。乙方应于三十日内付款。";
            String quote = "乙方应于三十日内付款。";

            AlignmentResult r = aligner.align(quote, identity(original));

            assertThat(r.matched()).isFalse();
            assertThat(r.failure())
                    .withFailMessage("同一引文出现多次时擅自选一个位置，会把人工引到错误的地方")
                    .isEqualTo(AlignmentFailure.AMBIGUOUS);
            assertThat(r.start()).isNull();
        }

        @Test
        @DisplayName("引文过短（无证据价值）→ TOO_SHORT，不给位置")
        void tooShortQuoteShouldFail() {
            String original = "甲方与乙方就本合同达成一致。";
            // "甲方"能命中全文，但作为风险条款的证据毫无价值
            AlignmentResult r = aligner.align("甲方", identity(original));

            assertThat(r.matched()).isFalse();
            assertThat(r.failure()).isEqualTo(AlignmentFailure.TOO_SHORT);
        }

        @Test
        @DisplayName("空引文或空原文 → INVALID_INPUT")
        void blankInputShouldFail() {
            assertThat(aligner.align("", identity("有内容")).failure())
                    .isEqualTo(AlignmentFailure.INVALID_INPUT);
            assertThat(aligner.align("   ", identity("有内容")).failure())
                    .isEqualTo(AlignmentFailure.INVALID_INPUT);
            assertThat(aligner.align("有内容", identity("")).failure())
                    .isEqualTo(AlignmentFailure.INVALID_INPUT);
            assertThat(aligner.align(null, identity("有内容")).failure())
                    .isEqualTo(AlignmentFailure.INVALID_INPUT);
        }

        @Test
        @DisplayName("构造未命中结果时若硬塞位置，直接抛异常")
        void resultMustRejectLocationOnMiss() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new AlignmentResult(
                            false, MatchLevel.NONE, 1, 5, "q", null, 0.0, false,
                            AlignmentFailure.NOT_FOUND))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不得携带位置");
        }

        @Test
        @DisplayName("构造命中结果时若不给区间，直接抛异常")
        void resultMustRequireLocationOnHit() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> new AlignmentResult(
                            true, MatchLevel.EXACT, null, null, "q", "q", 1.0, false, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须给出原文区间");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("多处命中：可消歧时取上下文最匹配的一处")
    class Disambiguation {

        @Test
        @DisplayName("提供了上下文时，多处命中不再判 AMBIGUOUS，而是选出最匹配的一处")
        void contextShouldDisambiguate() {
            String original = "第一条 乙方应于三十日内付款。第二条 乙方应于三十日内交付货物。";
            // 引文本身重复，但带上了上一句作为上下文，就能定位到第一处
            AlignmentResult r = aligner.alignWithContext(
                    "第一条 乙方应于三十日内付款。", "乙方应于三十日内付款。", identity(original));

            assertThat(r.matched()).isTrue();
            assertThat(r.start()).isLessThan(10);
        }
    }

    // ==================================================================
    // 辅助：把原文按与生产一致的方式归一化，并构造映射
    // ==================================================================

    /**
     * 用与生产同一套归一化逻辑构造映射。
     *
     * <p>测试里刻意复用 {@code TextNormalizer} 而不是自己造一个假映射：
     * 否则测的是"我假想的归一化"，而真实链路的归一化一旦改动，这里测不出来。
     */
    private static EvidenceAligner.OriginalMap normalizeMap(String original) {
        var normalized = new com.demo.contract.parse.text.TextNormalizer().normalize(original, 1);
        return new EvidenceAligner.OriginalMap(
                normalized.text(), normalized.offsetsToOriginal(), original);
    }
}
