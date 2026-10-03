package com.demo.contract.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端入口重定向。
 *
 * <p>存在的理由很具体：{@code /app/} 这种"目录形式"的请求由 Spring Boot 的
 * 静态资源处理器接管，<b>行为不确定</b>——实测它返回 500，
 * 而 {@code /app/index.html} 正常。
 *
 * <p>用户记住并输入的往往是短路径，所以这里显式重定向到登录页。
 * <b>不依赖框架的目录索引行为</b>：那种行为在不同版本、不同配置下会变，
 * 而这种失败恰好发生在演示时最尴尬的地方——第一屏。
 *
 * <p>提示：{@code redirect:} 前缀由 Spring MVC 处理，返回 302。
 */
@Controller
public class FrontendEntryController {

    @GetMapping({"/app", "/app/"})
    public String appEntry() {
        return "redirect:/app/index.html";
    }
}
