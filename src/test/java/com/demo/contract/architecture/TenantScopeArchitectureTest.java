package com.demo.contract.architecture;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构守卫：多租户 SQL 必须带 {@code tenant_id}（不变式 I-01）。
 *
 * <p>这可能是整个项目里最有价值的一个测试。原因：
 * "每个查询都记得写租户条件"是一条<b>靠自觉</b>的规矩，而自觉的失效率是 100%——
 * 不是今天漏，就是三个月后加新接口时漏。而漏掉的后果是跨租户数据泄露，
 * 属于最严重的一类缺陷。
 *
 * <p>所以把这条规矩变成构建时检查：新增任何 Mapper 方法而忘了带租户条件，
 * {@code mvn test} 直接失败，附上具体是哪个类哪个方法。
 *
 * <p>例外必须显式登记在 {@link #TENANT_FREE_ALLOWLIST} 里并写明理由。
 * <b>没有例外，只有登记过的例外。</b>
 */
class TenantScopeArchitectureTest {

    private static final String MAPPER_PACKAGE = "com.demo.contract";

    /**
     * 允许不带 {@code tenant_id} 的 Mapper 及其理由。
     *
     * <p>目前只有一处：登录时还不知道用户属于哪个租户，必须先按用户名定位再取出租户。
     * 这是<b>先有鸡还是先有蛋</b>的固有问题，无法规避，只能登记并说明。
     */
    private static final Map<String, String> TENANT_FREE_ALLOWLIST = Map.of(
            "com.demo.contract.auth.mapper.UserMapper",
            "登录入口：此时尚未确定租户，必须先按用户名查再取出租户"
    );

    /** 不含用户数据的表，或按主键查询而不涉及列表泄露的场景，可豁免。 */
    private static final Set<String> TENANT_FREE_TABLES = Set.of(
            "tenant"    // 租户表自身以 id 为主键，查询它不需要"限定在某个租户内"
    );

    @Test
    @DisplayName("每个 Mapper 的每条 SQL 都必须限定 tenant_id，例外须显式登记")
    void everyMapperSqlMustBeTenantScoped() {
        List<String> violations = new ArrayList<>();
        Map<String, String> allowed = new LinkedHashMap<>();

        for (Class<?> mapper : findMapperInterfaces()) {
            if (TENANT_FREE_ALLOWLIST.containsKey(mapper.getName())) {
                allowed.put(mapper.getName(), TENANT_FREE_ALLOWLIST.get(mapper.getName()));
                continue;
            }
            for (String sql : collectSql(mapper)) {
                if (sqlTouchesTenantFreeTable(sql)) {
                    continue;
                }
                if (!sql.contains("tenant_id")) {
                    violations.add(mapper.getSimpleName() + " 的某条 SQL 缺少 tenant_id:\n    "
                            + sql.replaceAll("\\s+", " ").trim());
                }
            }
        }

        assertThat(violations)
                .withFailMessage("""
                        发现未限定租户的 SQL（不变式 I-01）：

                        %s

                        处理方式二选一：
                          1) 在 SQL 中加上 tenant_id = #{tenantId} 条件；
                          2) 若确实无法限定，把该 Mapper 登记到
                             TenantScopeArchitectureTest.TENANT_FREE_ALLOWLIST 并写明理由。
                        """, String.join("\n\n", violations))
                .isEmpty();

        // 允许清单不能悄悄变大：这里固定住当前已知的例外数量
        assertThat(allowed)
                .withFailMessage("""
                        免租户 Mapper 的清单发生了变化。这是需要复核的架构改动，
                        请确认新增的例外确实无法限定租户，并同步更新本测试的期望。
                        当前：%s
                        """, allowed)
                .hasSize(1);
    }

    @Test
    @DisplayName("扫描确实找到了 Mapper（避免测试因为扫不到类而假绿）")
    void shouldActuallyFindMappers() {
        List<Class<?>> mappers = findMapperInterfaces();
        assertThat(mappers)
                .withFailMessage("没有扫到任何 Mapper，说明扫描逻辑失效，上面的测试就是假绿")
                .isNotEmpty();
        assertThat(mappers).extracting(Class::getSimpleName)
                .contains("ContractMapper", "ContractTextMapper", "UserMapper");
    }

    // ------------------------------------------------------------------

    private List<Class<?>> findMapperInterfaces() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                // ⚠️ 必须覆写：默认实现会排除接口，而 MyBatis 的 Mapper 全是接口。
                // 不覆写的话本测试会"扫到 0 个 Mapper"从而假绿——
                // 这也是为什么下面单独写了一个 shouldActuallyFindMappers 来防止这种假绿。
                return beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Mapper.class));

        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(MAPPER_PACKAGE)) {
            try {
                found.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("无法加载 Mapper 类: " + candidate.getBeanClassName(), e);
            }
        }
        return found;
    }

    /** 取出该类上所有 MyBatis 注解里的 SQL 文本（含 {@code <script>} 动态 SQL）。 */
    private List<String> collectSql(Class<?> mapper) {
        List<String> sqls = new ArrayList<>();
        for (Method method : mapper.getDeclaredMethods()) {
            for (var annotation : method.getAnnotations()) {
                String value = extractSql(annotation);
                if (value != null) {
                    sqls.add(value);
                }
            }
        }
        return sqls;
    }

    private String extractSql(java.lang.annotation.Annotation annotation) {
        if (annotation instanceof Select a) {
            return String.join(" ", a.value());
        }
        if (annotation instanceof Insert a) {
            return String.join(" ", a.value());
        }
        if (annotation instanceof Update a) {
            return String.join(" ", a.value());
        }
        if (annotation instanceof Delete a) {
            return String.join(" ", a.value());
        }
        return null;
    }

    private boolean sqlTouchesTenantFreeTable(String sql) {
        String lower = sql.toLowerCase();
        for (String table : TENANT_FREE_TABLES) {
            // 形如 " from tenant " 或 " into tenant "，避免误匹配 tenant_id
            if (lower.contains("from " + table + " ") || lower.contains("into " + table + " ")) {
                return true;
            }
        }
        return false;
    }
}
