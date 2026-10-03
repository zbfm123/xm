package com.demo.contract.web;

import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自带调试台页面的可访问性与编码测试。
 *
 * <p><b>这里踩过一个测试写法的坑，值得记下来：</b>
 * 最初用 {@code @AutoConfigureMockMvc} + {@code mockMvc.perform(get("/"))}，
 * 结果响应体是空的——<b>MockMvc 不加载静态资源</b>（它走的是 mock 的
 * ServletContext，没有真实容器那么完整的资源解析）。于是断言在空字符串上失败，
 * 看起来像"页面中文坏了"，实际上测的是"没有任何内容"。
 *
 * <p>改为 {@code RANDOM_PORT} + {@link TestRestTemplate} 起真实内嵌容器发 HTTP 请求，
 * 这样才能真正验证静态资源托管与字符集声明。
 *
 * <p>另外：这一轮我用 PowerShell 与 Python 反复核对页面中文，得到互相矛盾的结果，
 * 本质是控制台编码在捣乱。**结论：判断编码问题要看字节，不要看终端的脸。**
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class DebugPageTest {

    @Autowired
    private TestRestTemplate rest;

    /** 真实端口，供用 JDK HttpClient 的用例拼 URL。 */
    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Test
    @DisplayName("调试台页面可匿名访问，且中文内容与字符集声明正确")
    void debugPageShouldBePublicAndCorrectlyEncoded() {
        ResponseEntity<byte[]> res = rest.getForEntity("/", byte[].class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        String html = new String(res.getBody(), StandardCharsets.UTF_8);

        assertThat(html).contains("合同智能审查平台");
        assertThat(html).contains("开发调试台");
        // 演示口令必须写在页面上，否则使用者不知道输入什么
        assertThat(html).contains("staff01");
        assertThat(html).contains("Demo@2026");
        // 规则校验区块必须在，且明确区分三态
        assertThat(html).contains("规则校验");
        assertThat(html).contains("无法判定");
        // 页面自带 charset 声明，避免浏览器按系统编码猜
        assertThat(html).containsIgnoringCase("charset=UTF-8");
    }

    @Test
    @DisplayName("规则校验接口需要认证（未登录不得执行，也不得读取结论）")
    void ruleCheckEndpointRequiresAuth() throws Exception {
        // 用 JDK HttpClient 而不是 TestRestTemplate：
        // 后者默认的流式请求模式在遇到 401 + 未读取请求体时会抛
        // "cannot retry due to server authentication, in streaming mode"，
        // 那是客户端行为，不是服务端缺陷。HttpClient 对错误状态码更直白。
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        String base = "http://localhost:" + port;

        var post = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(base + "/api/contracts/999999/rule-check"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        var postRes = client.send(post, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(postRes.statusCode()).isEqualTo(401);

        var get = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(base + "/api/contracts/999999/findings"))
                .GET()
                .build();
        var getRes = client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(getRes.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("调试台可匿名访问，但业务接口仍然需要认证")
    void apisMustRemainProtected() {
        assertThat(rest.getForEntity("/api/contracts", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/auth/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // 健康检查是公开的
        assertThat(rest.getForEntity("/api/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
