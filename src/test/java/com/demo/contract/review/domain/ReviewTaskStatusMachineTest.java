package com.demo.contract.review.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审查任务状态机的穷举测试（T-017）。
 *
 * <p>验收要求是"合法迁移有穷举单测"。这里把 6×6 = 36 个状态对
 * <b>全部</b>枚举出来逐个断言，因此新增状态时不可能漏测。
 *
 * <p>穷举的意义：状态机的缺陷几乎总是"某条边被漏掉了"或"某条边被意外打开了"，
 * 而只测几条典型路径是发现不了的。
 */
class ReviewTaskStatusMachineTest {

    /** 期望的合法迁移。刻意与生产代码分开写一遍，否则测试只是复述实现。 */
    private static final Set<String> EXPECTED_LEGAL = Set.of(
            "PENDING->IN_PROGRESS",
            "PENDING->CANCELLED",
            "IN_PROGRESS->AWAITING_REVIEW",
            "IN_PROGRESS->AI_UNAVAILABLE",
            "IN_PROGRESS->CANCELLED",
            "AI_UNAVAILABLE->AWAITING_REVIEW",
            "AI_UNAVAILABLE->CANCELLED",
            "AWAITING_REVIEW->COMPLETED",
            "AWAITING_REVIEW->CANCELLED"
    );

    @Test
    @DisplayName("穷举全部 6×6 状态对：合法迁移集合与预期完全一致")
    void allStatusPairsShouldMatchExpectedTable() {
        Set<String> actual = new LinkedHashSet<>();
        for (ReviewTaskStatus from : ReviewTaskStatus.values()) {
            for (ReviewTaskStatus to : ReviewTaskStatus.values()) {
                if (from.canMoveTo(to)) {
                    actual.add(from.name() + "->" + to.name());
                }
            }
        }

        assertThat(actual)
                .withFailMessage("""
                        状态机与预期迁移表不一致。

                        多出来的（被意外打开）: %s
                        少掉的（被漏掉了）:     %s
                        """,
                        difference(actual, EXPECTED_LEGAL),
                        difference(EXPECTED_LEGAL, actual))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_LEGAL);
    }

    @Test
    @DisplayName("穷举全部 6×6 状态对：合法则 canMoveTo=true，非法则为 false")
    void everyPairShouldBeAssertedIndividually() {
        for (ReviewTaskStatus from : ReviewTaskStatus.values()) {
            for (ReviewTaskStatus to : ReviewTaskStatus.values()) {
                boolean expected = EXPECTED_LEGAL.contains(from.name() + "->" + to.name());
                assertThat(from.canMoveTo(to))
                        .withFailMessage("%s -> %s 期望 %s，实际 %s",
                                from, to, expected, !expected)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("AI_UNAVAILABLE 必须能回到待人工复核 —— 这是不变式 I-04 的落点")
    void aiUnavailableMustNotBeTerminal() {
        assertThat(ReviewTaskStatus.AI_UNAVAILABLE.isTerminal())
                .withFailMessage("""
                        AI 不可用被设成了终态。

                        这等于"AI 挂了整份审查就废了"，直接违反 I-04
                        （AI 不可用时规则结论与人工流程必须不受影响）。
                        它必须能迁移到 AWAITING_REVIEW。
                        """)
                .isFalse();
        assertThat(ReviewTaskStatus.AI_UNAVAILABLE.canMoveTo(ReviewTaskStatus.AWAITING_REVIEW))
                .isTrue();
    }

    @Test
    @DisplayName("终态没有任何出边 —— 包括不能取消一个已完成的任务")
    void terminalStatusesShouldHaveNoOutgoingEdges() {
        for (ReviewTaskStatus s : ReviewTaskStatus.values()) {
            if (s.isTerminal()) {
                assertThat(s.allowedTargets())
                        .withFailMessage("终态 %s 仍有出边: %s", s, s.allowedTargets())
                        .isEmpty();
                for (ReviewTaskStatus to : ReviewTaskStatus.values()) {
                    assertThat(s.canMoveTo(to)).isFalse();
                }
            } else {
                assertThat(s.allowedTargets()).isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("只有 COMPLETED 与 CANCELLED 是终态")
    void onlyTwoTerminalStatuses() {
        Set<ReviewTaskStatus> terminal = new LinkedHashSet<>();
        for (ReviewTaskStatus s : ReviewTaskStatus.values()) {
            if (s.isTerminal()) {
                terminal.add(s);
            }
        }
        assertThat(terminal).containsExactlyInAnyOrder(
                ReviewTaskStatus.COMPLETED, ReviewTaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("不可自环：任何状态都不能迁移到自己")
    void noSelfLoops() {
        for (ReviewTaskStatus s : ReviewTaskStatus.values()) {
            assertThat(s.canMoveTo(s))
                    .withFailMessage("状态 %s 可以迁移到自己，会让「重复提交」被误判为合法推进", s)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("null 目标状态一律拒绝，而不是抛异常")
    void nullTargetShouldBeRejected() {
        for (ReviewTaskStatus s : ReviewTaskStatus.values()) {
            assertThat(s.canMoveTo(null)).isFalse();
        }
    }

    @Test
    @DisplayName("每个状态都有中文显示名，且不能回到 PENDING")
    void displayNamesShouldExist() {
        for (ReviewTaskStatus s : ReviewTaskStatus.values()) {
            assertThat(s.displayName()).isNotBlank();
            // 回到 PENDING 意味着"重来一遍"，会让已发生的处理与审计失去意义
            assertThat(s.canMoveTo(ReviewTaskStatus.PENDING))
                    .withFailMessage("状态 %s 可以回到 PENDING，会让已发生的处理被抹掉", s)
                    .isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,IN_PROGRESS,true",
            "IN_PROGRESS,AI_UNAVAILABLE,true",
            "AI_UNAVAILABLE,AWAITING_REVIEW,true",
            "AWAITING_REVIEW,COMPLETED,true",
            "PENDING,COMPLETED,false",
            "PENDING,AWAITING_REVIEW,false",
            "COMPLETED,CANCELLED,false",
            "CANCELLED,IN_PROGRESS,false",
            "COMPLETED,AWAITING_REVIEW,false"
    })
    @DisplayName("关键迁移的显式用例（含几条最容易被写错的）")
    void keyTransitionsShouldBeExplicit(String from, String to, boolean expected) {
        assertThat(ReviewTaskStatus.valueOf(from).canMoveTo(ReviewTaskStatus.valueOf(to)))
                .isEqualTo(expected);
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> d = new LinkedHashSet<>(a);
        d.removeAll(b);
        return d;
    }
}
