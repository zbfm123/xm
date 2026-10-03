package com.demo.contract.parse.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文本归一化单元测试。
 *
 * <p>本测试的核心不是"文本被洗干净了"，而是<b>坐标映射仍然正确</b>。
 * 原因是这两件事的失败方式完全不同：
 * <ul>
 *   <li>文本没洗净 → 肉眼可见，调试时立刻发现</li>
 *   <li>映射错位 → <b>文本看起来完全正常</b>，但所有证据区间整体偏移，
 *       要到下游"AI 引文定位不上"时才暴露，而且很难定位到是归一化的问题</li>
 * </ul>
 * 所以这里大量断言"归一化片段 → 回查原文"的结果。
 */
class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    // ==================================================================
    // 基础
    // ==================================================================

    @Test
    @DisplayName("纯 ASCII 文本归一化后保持不变，映射为恒等")
    void asciiTextShouldBeUnchanged() {
        String raw = "Party A agrees to pay 1000 USD.";
        NormalizedText n = normalizer.normalize(raw);

        assertThat(n.text()).isEqualTo(raw);
        assertThat(n.length()).isEqualTo(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            assertThat(n.offsetsToOriginal()[i]).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("归一化后映射数组长度必须与文本长度一致")
    void mappingLengthMustMatchText() {
        NormalizedText n = normalizer.normalize("甲　方：ＡＢＣ\r\n\r\n\r\n乙　方：ＸＹＺ");
        assertThat(n.offsetsToOriginal()).hasSize(n.text().length());
        n.assertConsistent();
    }

    // ==================================================================
    // 换行与空白
    // ==================================================================

    @Test
    @DisplayName("CRLF 统一为 LF：\\r\\n 只产生一个换行")
    void crlfShouldBecomeSingleLf() {
        NormalizedText n = normalizer.normalize("line1\r\nline2\r\nline3");
        assertThat(n.text()).isEqualTo("line1\nline2\nline3");
    }

    @Test
    @DisplayName("单独出现的 CR 也统一为 LF")
    void loneCrShouldBecomeLf() {
        NormalizedText n = normalizer.normalize("a\rb");
        assertThat(n.text()).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("行内连续空格折叠为一个，映射指向第一个空格")
    void multipleSpacesShouldCollapse() {
        // 用纯 ASCII 以便逐字符校验映射
        String raw = "ABC     DEF";
        NormalizedText n = normalizer.normalize(raw);

        assertThat(n.text()).isEqualTo("ABC DEF");
        // "ABC" 三位映射不变
        assertThat(n.offsetsToOriginal()[0]).isZero();
        assertThat(n.offsetsToOriginal()[2]).isEqualTo(2);
        // 折叠出的那个空格应指向原文第一个空格（下标 3）
        assertThat(n.offsetsToOriginal()[3]).isEqualTo(3);
        // "DEF" 应指向原文下标 8 起的字符
        assertThat(n.offsetsToOriginal()[4]).isEqualTo(8);
        assertThat(n.offsetsToOriginal()[6]).isEqualTo(10);
    }

    @Test
    @DisplayName("行尾空白被删除")
    void trailingSpacesShouldBeRemoved() {
        NormalizedText n = normalizer.normalize("line one   \nline two\t\t\n");
        assertThat(n.text()).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("多个空行折叠为一个换行")
    void blankLinesShouldCollapse() {
        NormalizedText n = normalizer.normalize("A\n\n\n\nB");
        assertThat(n.text()).isEqualTo("A\nB");
    }

    @Test
    @DisplayName("行首缩进不产生空格")
    void leadingIndentShouldNotProduceSpace() {
        NormalizedText n = normalizer.normalize("A\n     B");
        assertThat(n.text()).isEqualTo("A\nB");
    }

    @Test
    @DisplayName("制表符按空白处理")
    void tabsShouldBeTreatedAsWhitespace() {
        NormalizedText n = normalizer.normalize("金额\t\t1000");
        assertThat(n.text()).isEqualTo("金额 1000");
    }

    // ==================================================================
    // 全角转半角
    // ==================================================================

    @Test
    @DisplayName("全角 ASCII 转半角，映射仍指向原字符位置")
    void fullwidthAsciiShouldConvert() {
        String raw = "金额：１２８０００元";
        NormalizedText n = normalizer.normalize(raw);

        assertThat(n.text()).isEqualTo("金额：128000元");
        // 长度不变（一对一转换），因此映射应当是恒等
        assertThat(n.text()).hasSameSizeAs(raw);
        for (int i = 0; i < raw.length(); i++) {
            assertThat(n.offsetsToOriginal()[i]).isEqualTo(i);
        }
        // 回查原文：取归一化后的数字区间，应能定位回全角数字
        int start = n.text().indexOf("128000");
        int[] range = n.toOriginalRange(start, start + 6);
        assertThat(n.originalSlice(range[0], range[1])).isEqualTo("１２８０００");
    }

    @Test
    @DisplayName("中文标点刻意不转半角（避免破坏语义与排版）")
    void chinesePunctuationShouldBePreserved() {
        String raw = "甲方，乙方。丙方；丁方：戊方（己方）";
        NormalizedText n = normalizer.normalize(raw);
        assertThat(n.text()).isEqualTo(raw);
    }

    @Test
    @DisplayName("全角空格转半角空格后再参与折叠")
    void fullwidthSpaceShouldCollapse() {
        NormalizedText n = normalizer.normalize("甲　方　　名称");
        assertThat(n.text()).isEqualTo("甲 方 名称");
    }

    // ==================================================================
    // 坐标回查（本测试最重要的部分）
    // ==================================================================

    @Test
    @DisplayName("组合归一化后，任意归一化片段都能回查到对应的原文片段")
    void normalizedSliceShouldMapBackToOriginal() {
        String raw = "第一条\r\n\r\n甲方：　　北京某某科技有限公司\r\n乙方：　　上海某某贸易有限公司\r\n";
        NormalizedText n = normalizer.normalize(raw);

        // 回查"乙方"所在位置
        int idx = n.text().indexOf("上海某某贸易有限公司");
        assertThat(idx).isGreaterThanOrEqualTo(0);

        int[] range = n.toOriginalRange(idx, idx + "上海某某贸易有限公司".length());
        String original = n.originalSlice(range[0], range[1]);

        assertThat(original)
                .withFailMessage("回查原文失败：期望包含上海某某贸易有限公司，实得 %s", original)
                .contains("上海某某贸易有限公司");
    }

    @Test
    @DisplayName("回查区间是单调的：归一化位置越靠后，原文位置不会更靠前")
    void mappingShouldBeMonotonic() {
        String raw = "甲　方：Ａ公司\r\n\r\n乙　方：Ｂ公司\r\n金额：１２３４５元";
        NormalizedText n = normalizer.normalize(raw);

        int previous = -1;
        for (int i = 0; i < n.length(); i++) {
            int origin = n.offsetsToOriginal()[i];
            assertThat(origin)
                    .withFailMessage("映射非单调：下标 %d 指向原文 %d，前一个字符指向 %d", i, origin, previous)
                    .isGreaterThanOrEqualTo(previous);
            previous = origin;
        }
    }

    @Test
    @DisplayName("越界区间直接抛异常，不返回'尽量接近'的结果")
    void outOfRangeShouldThrow() {
        NormalizedText n = normalizer.normalize("短文本");

        assertThatThrownBy(() -> n.toOriginalRange(-1, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> n.toOriginalRange(0, 100))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> n.toOriginalRange(3, 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // ==================================================================
    // 页眉页脚
    // ==================================================================

    @Test
    @DisplayName("多页文档中重复出现的短行被识别为页眉并删除；正文与页码保留")
    void repeatedHeadersShouldBeRemovedInMultiPageDocument() {
        String raw = String.join("\n",
                "某某公司法务部",                     // 页眉：三页完全相同
                "第一页正文内容：甲方应于签署后付款。",
                "第 1 页",                            // 页码：每页不同
                "某某公司法务部",
                "第二页正文内容：乙方应于收到货物后验收。",
                "第 2 页",
                "某某公司法务部",
                "第三页正文内容：争议提交仲裁委员会解决。",
                "第 3 页");

        NormalizedText n = normalizer.normalize(raw, 3);

        // 完全相同的重复短行被删
        assertThat(n.text()).doesNotContain("某某公司法务部");

        // 正文必须完整保留
        assertThat(n.text()).contains("第一页正文内容：甲方应于签署后付款。");
        assertThat(n.text()).contains("第二页正文内容：乙方应于收到货物后验收。");
        assertThat(n.text()).contains("第三页正文内容：争议提交仲裁委员会解决。");

        // 页码每页都不同，因此"重复行"判定抓不到它——这是刻意保留的保守行为。
        // 误删正文的代价远高于留下页码：留下的页码人一眼能忽略，删掉的正文没人能找回来。
        assertThat(n.text()).contains("第 1 页").contains("第 3 页");
    }

    @Test
    @DisplayName("页码形式不同时也不会误删正文（保守行为的边界）")
    void varyingFooterTextShouldNotCauseBodyLoss() {
        String raw = String.join("\n",
                "第一页正文甲",
                "第 1 页 / 共 3 页",
                "第二页正文乙",
                "第 2 页 / 共 3 页",
                "第三页正文丙",
                "第 3 页 / 共 3 页");

        NormalizedText n = normalizer.normalize(raw, 3);

        assertThat(n.text())
                .contains("第一页正文甲")
                .contains("第二页正文乙")
                .contains("第三页正文丙");
    }

    @Test
    @DisplayName("单页文档不做页眉页脚清理（避免误删）")
    void singlePageDocumentShouldNotRemoveRepeatedLines() {
        String raw = "重复的短行\n重复的短行\n重复的短行\n正文";
        NormalizedText n = normalizer.normalize(raw, 1);

        // 单页时保留全部内容
        assertThat(n.text()).contains("重复的短行");
    }

    @Test
    @DisplayName("超长重复行不当作页眉（长句重复更像标准条款，误删代价高）")
    void longRepeatedLinesShouldBeKept() {
        // 正好 40 字：曾经因为阈值设成 <= 40 而被误删，现在上限是严格的 < 30
        String longClause = "本合同的任何一方未经对方书面同意不得将本合同项下的权利义务全部或部分转让给第三方";
        String raw = String.join("\n",
                longClause,
                "第一页正文",
                longClause,
                "第二页正文",
                longClause,
                "第三页正文");

        NormalizedText n = normalizer.normalize(raw, 3);
        assertThat(n.text())
                .withFailMessage("重复的长句被当成页眉删掉了：%s", n.text())
                .contains(longClause);
    }

    @Test
    @DisplayName("阈值边界：正好等于上限的重复短行保守保留")
    void lineAtLengthBoundaryShouldBeKept() {
        // 30 字，正好等于上限（上限是严格小于，因此不处理）
        String boundary = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        assertThat(boundary).hasSize(30);

        String raw = String.join("\n", boundary, "正文甲", boundary, "正文乙", boundary, "正文丙");
        NormalizedText n = normalizer.normalize(raw, 3);

        assertThat(n.text()).contains(boundary);
    }

    @Test
    @DisplayName("删除页眉后，正文片段仍能回查原文")
    void mappingShouldSurviveHeaderRemoval() {
        String raw = String.join("\n",
                "页眉公司名",
                "第一页甲方名称：北京某某科技有限公司",
                "页眉公司名",
                "第二页乙方名称：上海某某贸易有限公司",
                "页眉公司名",
                "第三页金额：１２８０００元");

        NormalizedText n = normalizer.normalize(raw, 3);

        int idx = n.text().indexOf("上海某某贸易有限公司");
        int[] range = n.toOriginalRange(idx, idx + "上海某某贸易有限公司".length());
        assertThat(n.originalSlice(range[0], range[1])).contains("上海某某贸易有限公司");
    }

    // ==================================================================
    // 边界
    // ==================================================================

    @Test
    @DisplayName("空文本与纯空白文本不会抛异常")
    void emptyInputShouldNotThrow() {
        assertThat(normalizer.normalize("").text()).isEmpty();
        assertThat(normalizer.normalize("   \r\n\t  ").text()).isEmpty();
    }

    @Test
    @DisplayName("null 输入明确抛异常，而不是返回空文本")
    void nullInputShouldThrow() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("摘要能反映删除了多少字符")
    void summaryShouldReportRemovedCharacters() {
        NormalizedText n = normalizer.normalize("A\r\n\r\n\r\nB      C");
        assertThat(n.summary()).containsKeys("originalLength", "normalizedLength", "charsRemoved");
        assertThat((Integer) n.summary().get("charsRemoved")).isGreaterThan(0);
    }
}
