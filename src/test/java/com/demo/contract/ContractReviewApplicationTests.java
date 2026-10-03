package com.demo.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-001 骨架自检：证明"应用能起来、配置能读到、建表脚本能执行"。
 *
 * <p>这是一个集成测试而非单元测试，因为 T-001 要验证的恰恰是
 * 组件装配与配置绑定这类"只有跑起来才知道"的事情。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractReviewApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("应用上下文能加载（配置绑定、建表脚本、Mapper 扫描均无异常）")
    void contextLoads() {
        // 上下文加载失败时本测试直接失败，无需断言
    }

    @Test
    @DisplayName("健康检查端点返回 UP，且暴露当前 AI 通道状态")
    void healthEndpointReportsUpAndAiState() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("contract-review"))
                // 测试环境必须走 Mock 桩，绝不能真调模型（额度保护，决策 D-12）
                .andExpect(jsonPath("$.aiEnabled").value(false));
    }
}
