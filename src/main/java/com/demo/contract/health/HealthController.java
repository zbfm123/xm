package com.demo.contract.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨架自检端点（属于 T-001 / T-002 的验收证据）。
 *
 * <p>它的存在意义不是"业务功能"，而是回答一个问题：
 * <b>应用跑起来了吗、配置文件读到了吗、AI 通道当前是什么状态？</b>
 *
 * <p>特意把 {@code aiEnabled} 暴露出来：演示时一眼就能看出当前走的是
 * 真实调用还是 Mock 桩（决策 D-12），避免"以为在真调，其实在读缓存"这类误判。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final boolean aiEnabled;
    private final String aiModel;
    private final String promptVersion;

    public HealthController(
            @org.springframework.beans.factory.annotation.Value("${app.ai.enabled}") boolean aiEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.ai.model}") String aiModel,
            @org.springframework.beans.factory.annotation.Value("${app.ai.prompt-version}") String promptVersion) {
        this.aiEnabled = aiEnabled;
        this.aiModel = aiModel;
        this.promptVersion = promptVersion;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", "contract-review");
        body.put("time", OffsetDateTime.now().toString());
        body.put("aiEnabled", aiEnabled);
        body.put("aiModel", aiModel);
        body.put("promptVersion", promptVersion);
        return ResponseEntity.ok(body);
    }
}
