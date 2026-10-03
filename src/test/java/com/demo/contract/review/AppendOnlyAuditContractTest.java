package com.demo.contract.review;

import com.demo.contract.review.mapper.ReviewActionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只追加不变式（I-06）的结构性测试。
 *
 * <p><b>本测试不验证行为，而是验证"不存在修改入口"。</b>
 *
 * <p>为什么这比行为测试更重要：行为测试只能覆盖我想到的场景，
 * 而"将来某个人给 Mapper 加一个 deleteByContract"是我想不到的。
 * 用反射扫描接口，让这类改动脉络在<b>构建时</b>就失败。
 *
 * <p>这也是为什么该约束不能只写在文档或注释里——
 * 注释不会阻止任何人，构建失败会。
 */
class AppendOnlyAuditContractTest {

    /** 允许出现的方法名前缀。新增方法若不在此列，测试失败。 */
    private static final List<String> ALLOWED_PREFIXES = List.of("insert", "find");

    @Test
    @DisplayName("ReviewActionMapper 只能 insert 与 find —— 不允许任何 update/delete 入口")
    void mapperMustNotExposeMutators() {
        List<String> offenders = Arrays.stream(ReviewActionMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> ALLOWED_PREFIXES.stream().noneMatch(name::startsWith))
                .toList();

        assertThat(offenders)
                .withFailMessage("""
                        ReviewActionMapper 出现了非追加型方法：%s

                        审计记录只追加（不变式 I-06）。撤销一个动作的正确做法是
                        **追加一条反向动作**，而不是修改或删除原记录。
                        如果确实需要新的读取方法，请以 find 开头，并更新本测试的白名单。
                        """, offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("该扫描确实扫到了方法 —— 否则上面的断言是假绿")
    void scanShouldActuallyFindMethods() {
        assertThat(ReviewActionMapper.class.getDeclaredMethods())
                .withFailMessage("没有扫到任何方法，说明反射扫描本身失效，"
                        + "上一条断言会无意义地通过")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("只追加表不得出现 update/delete 关键字")
    void noSqlShouldContainMutatingKeywords() {
        List<String> offenders = Arrays.stream(ReviewActionMapper.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getAnnotations()))
                .map(Object::toString)
                .map(String::toUpperCase)
                .filter(sql -> sql.contains("UPDATE ")
                        || sql.contains("DELETE FROM")
                        || sql.contains("TRUNCATE"))
                .toList();

        assertThat(offenders)
                .withFailMessage("审计表的 SQL 出现了修改或删除语句：%s", offenders)
                .isEmpty();
    }
}
