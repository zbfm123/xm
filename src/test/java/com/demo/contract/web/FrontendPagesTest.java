package com.demo.contract.web;

import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式前端页面（T-020）的可用性测试。
 *
 * <p>测的是"页面真的能被浏览器加载"这件事——静态资源路径写错、
 * 文件放错目录、编码不对，靠人工点才能发现，而这类问题
 * <b>恰恰在演示前一天最容易出现</b>。
 *
 * <p>用 {@code RANDOM_PORT} 起真实容器：<b>MockMvc 不加载 static 目录</b>，
 * 之前为此踩过坑（返回空 body）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class FrontendPagesTest {

    @Autowired private TestRestTemplate rest;

    private String get(String path) {
        var res = rest.getForEntity(path, String.class);
        assertThat(res.getStatusCode())
                .withFailMessage("页面 %s 无法访问，状态 %s", path, res.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String body = res.getBody();
        assertThat(body).isNotNull();
        // 用字节长度判断，避免把"空文件"当成加载成功
        assertThat(body.getBytes(StandardCharsets.UTF_8).length)
                .withFailMessage("页面 %s 返回了空内容", path)
                .isGreaterThan(200);
        return body;
    }

    @Test
    @DisplayName("四个页面都能被真实容器加载")
    void allPagesShouldBeServed() {
        get("/app/index.html");
        get("/app/contracts.html");
        get("/app/detail.html");
        get("/app/report.html");
    }

    @Test
    @DisplayName("/app/ 目录默认返回登录页")
    void appRootShouldServeLoginPage() {
        String html = get("/app/");
        assertThat(html).contains("登录");
        assertThat(html).contains("staff01");
    }

    @Test
    @DisplayName("共享资源（CSS / JS）可访问且非空")
    void sharedAssetsShouldBeServed() {
        String css = get("/app/assets/app.css");
        // CSS 里必须有"无法判定"用的灰色语义，否则三态在视觉上会退化成两态
        assertThat(css).contains("--unknown");
        assertThat(css).contains("@media print");

        String js = get("/app/assets/api.js");
        // 关键约定必须出现在前端代码里
        assertThat(js).contains("degradable");
        assertThat(js).contains("无法判定");
        assertThat(js).contains("UNDETERMINED");
    }

    @Test
    @DisplayName("登录页有演示账号提示，不需要使用者去翻文档")
    void loginPageShouldListDemoAccounts() {
        String html = get("/app/index.html");
        assertThat(html).contains("Demo@2026");
        assertThat(html).contains("staff01");
        assertThat(html).contains("lead01");
    }

    @Test
    @DisplayName("详情页包含三类结果的展示与复核入口")
    void detailPageShouldCoverAllSections() {
        String html = get("/app/detail.html");
        assertThat(html).contains("抽取到的要素");
        assertThat(html).contains("规则结论");
        assertThat(html).contains("AI 风险结论");
        assertThat(html).contains("人工复核记录");
        // 复核动作的入口
        assertThat(html).contains("采纳");
        assertThat(html).contains("驳回");
        // 审计链校验入口
        assertThat(html).contains("校验审计链");
        // 降级后的继续入口（I-04 的出口）
        assertThat(html).contains("继续进入人工复核");
    }

    @Test
    @DisplayName("报告页必须在报告正文里写明已知限制，而不是只写在文档里")
    void reportPageShouldStateItsLimits() {
        String html = get("/app/report.html");
        assertThat(html).contains("结论边界与已知限制");
        // 两条最容易被追问的边界
        assertThat(html)
                .withFailMessage("报告里没有说明哈希链不防抵赖——这是必须主动交代的边界")
                .contains("不防抵赖");
        assertThat(html)
                .withFailMessage("报告里没有说明规则级别没有法务依据")
                .contains("没有真实法务依据");
        // 打印支持
        assertThat(html).contains("打印");
    }

    @Test
    @DisplayName("页面里没有硬编码的租户或用户名 —— 数据一律来自接口")
    void pagesShouldNotHardcodeBusinessData() {
        for (String path : new String[]{"/app/contracts.html", "/app/detail.html", "/app/report.html"}) {
            String html = get(path);
            assertThat(html)
                    .withFailMessage("%s 里出现了硬编码的演示合同标题，"
                            + "页面数据必须全部来自接口", path)
                    .doesNotContain("演示-要素齐全")
                    .doesNotContain("北京某某科技有限公司");
        }
    }

    @Test
    @DisplayName("旧调试台仍可用（不因为新页面而失效）")
    void legacyDebugConsoleShouldStillWork() {
        String html = get("/");
        assertThat(html).contains("规则校验");
        assertThat(html).contains("evidence");
    }
}
