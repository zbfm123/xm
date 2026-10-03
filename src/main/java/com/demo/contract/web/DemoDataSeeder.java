package com.demo.contract.web;

import com.demo.contract.aireview.AiReviewService;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.auth.domain.Role;
import com.demo.contract.auth.mapper.UserMapper;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.review.ReviewTaskService;
import com.demo.contract.rule.RuleCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

/**
 * 演示数据准备（仅 dev 环境）。
 *
 * <p><b>解决一个很实际的问题：干净机器上启动后是空库，演示第一步就卡住。</b>
 * 演示只有 5 分钟，不该花在"先上传个文件"上。
 *
 * <p>启动时自动准备三份虚构合同并跑完整条链路：
 * <ol>
 *   <li>要素齐全、无风险条款 → 规则 4 条全通过，AI 无候选</li>
 *   <li>要素缺失 → 规则出现"无法判定"，用于讲三态设计</li>
 *   <li>含四类风险条款 → AI 产出候选，用于讲证据对齐与人工复核</li>
 * </ol>
 *
 * <p>三份合同对应三个面试讲点，<b>演示时按顺序点开就能把三个「绝不砍」项讲完</b>。
 *
 * <p>⚠️ 三条纪律：
 * <ul>
 *   <li>**只在 dev 环境**（{@code @Profile("dev")}），绝不能在生产建假数据</li>
 *   <li>**幂等**：按标题查重，重复启动不会建出多份</li>
 *   <li>**失败不影响启动**：演示数据准备不成功时只记日志，
 *       不能因为它的异常让应用起不来</li>
 * </ul>
 *
 * <p>AI 审查在降级通道下会失败，这是预期内的——那时只准备前两步，
 * 演示仍然可以讲规则与要素。
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "app.demo.seed", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /**
     * 演示合同的标题前缀，用于幂等查重。
     *
     * <p>⚠️ <b>刻意不含方括号。</b>
     *
     * <p>我最初用的是带方括号的版本，结果幂等判断始终失效，
     * 每次启动都重复建 3 份一模一样的演示合同。
     *
     * <p>根因很典型：<b>MyBatis 的 {@code #{}} 走的是 OGNL 表达式，
     * 而 OGNL 把方括号解析成数组/列表下标</b>——于是 LIKE 的参数
     * 根本不是那个字面量。
     *
     * <p>教训：<b>凡是会进 {@code #{}} 的字符串常量，都不该包含 OGNL 的元字符</b>
     * （方括号、点号、圆括号、逗号、单引号等）。
     * 这不是"绕过框架"，而是遵守它的真实语义。
     */
    private static final String PREFIX = "演示-";

    private final ContractService contractService;
    private final ContractParsingService parsingService;
    private final RuleCheckService ruleCheckService;
    private final ElementExtractionService extractionService;
    private final AiReviewService aiReviewService;
    private final ReviewTaskService taskService;
    private final UserMapper userMapper;

    public DemoDataSeeder(ContractService contractService,
                          ContractParsingService parsingService,
                          RuleCheckService ruleCheckService,
                          ElementExtractionService extractionService,
                          AiReviewService aiReviewService,
                          ReviewTaskService taskService,
                          UserMapper userMapper) {
        this.contractService = contractService;
        this.parsingService = parsingService;
        this.ruleCheckService = ruleCheckService;
        this.extractionService = extractionService;
        this.aiReviewService = aiReviewService;
        this.taskService = taskService;
        this.userMapper = userMapper;
    }

    @Bean
    public ApplicationRunner seedDemoData(
            @org.springframework.beans.factory.annotation.Value("${server.port:8080}") int port) {
        return args -> {
            try {
                seed(port);
            } catch (Exception e) {
                // 演示数据准备失败绝不能让应用起不来
                log.warn("演示数据准备失败（不影响应用使用，可手工上传）：{}", e.toString());
            } finally {
                // ⚠️ 必须清理：线程池会复用线程，不清理会让下一个请求串号
                CurrentUser.clear();
            }
        };
    }

    private void seed(int port) {
        var staff = userMapper.findByUsername("staff01");
        if (staff == null) {
            log.warn("找不到 staff01 账号，跳过演示数据准备");
            return;
        }

        // ⚠️ 必须在任何业务调用**之前**设置登录上下文。
        //
        // 我最初把 alreadySeeded() 写在了 CurrentUser.set 之前，
        // 于是幂等检查抛 AuthException（缺少租户上下文）被 catch 吞掉、
        // 判定为"未播种"，**每次启动都重复建 3 份演示合同**。
        //
        // 这类错误的隐蔽之处：它不报错，只是"看起来没数据"，
        // 于是安静地重复执行。现在异常会打 warn 并带上原因。
        CurrentUser.set(staff.getId(), staff.getTenantId(), staff.getUsername(), staff.getRole());

        // ⚠️ 逐个模板检查，而不是"有任意演示数据就整体跳过"。
        //
        // 后者有个实际会遇到的弱点：演示中有人删掉其中一份（或者在界面上
        // 点删除做演示），重启后**剩下两份会被当成"已播种"**，
        // 于是永远补不回第三份——演示集就残缺了，而日志显示一切正常。
        //
        // 逐份检查让播种变成"补齐缺失的那几份"，既不重复也不残缺。
        List<DemoContract> contracts = List.of(
                new DemoContract("要素齐全（无风险条款）", "well-formed"),
                new DemoContract("要素缺失（演示三态）", "sparse"),
                new DemoContract("含风险条款（演示 AI 与复核）", "risky"));

        List<DemoContract> missing = new ArrayList<>();
        for (DemoContract d : contracts) {
            if (!exists(staff.getTenantId(), d)) {
                missing.add(d);
            }
        }

        if (missing.isEmpty()) {
            log.info("演示数据已完整（3 份），跳过（幂等）");
            return;
        }

        log.info("开始准备演示数据：需补齐 {} 份（共 3 份）…", missing.size());
        for (DemoContract d : missing) {
            prepare(port, d.label(), d.template(), true);
        }

        log.info("演示数据准备完成。打开 http://localhost:{} 用 staff01 / Demo@2026 登录", port);
    }

    /** 一份演示合同的定义。 */
    private record DemoContract(String label, String template) {
    }

    /**
     * 检查某份演示合同是否已存在。
     *
     * <p>按<b>标题精确匹配</b>而不是模糊前缀：前缀匹配在中途删掉一份、
     * 又新增一份同名的情况下会误判。
     */
    private boolean exists(Long tenantId, DemoContract d) {
        try {
            var page = contractService.list(PREFIX + d.label(), null, 1, 20);
            boolean found = page.items().stream()
                    .anyMatch(c -> (PREFIX + d.label()).equals(c.title()));
            log.debug("演示数据检查: 《{}》 存在={} （tenant={}）", d.label(), found, tenantId);
            return found;
        } catch (RuntimeException e) {
            // ⚠️ 不能静默吞掉：幂等检查失败会导致每次启动都重复播种
            log.warn("演示数据检查失败，将按缺失处理（可能重复播种）: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /** 生成示例 PDF → 上传 → 解析 → 规则校验 →（尽力）AI 审查与任务。 */
    private void prepare(int port, String label, String template, boolean runAi) {
        try {
            byte[] pdf = fetchSample(port, template);
            if (pdf == null) {
                log.warn("取示例 PDF 失败，跳过：{}", label);
                return;
            }

            Long contractId = contractService.upload(
                    new org.springframework.web.multipart.MultipartFile() {
                        @Override public String getName() { return "file"; }
                        @Override public String getOriginalFilename() { return template + ".pdf"; }
                        @Override public String getContentType() { return "application/pdf"; }
                        @Override public boolean isEmpty() { return pdf.length == 0; }
                        @Override public long getSize() { return pdf.length; }
                        @Override public byte[] getBytes() { return pdf; }
                        @Override public java.io.InputStream getInputStream() {
                            return new java.io.ByteArrayInputStream(pdf);
                        }
                        @Override public void transferTo(java.io.File dest) throws java.io.IOException {
                            java.nio.file.Files.write(dest.toPath(), pdf);
                        }
                    },
                    PREFIX + label).contract().id();

            parsingService.parse(contractId);
            var rule = ruleCheckService.check(contractId);

            // ⚠️ 必须显式跑一次要素抽取，否则详情页的「抽取到的要素」是空的。
            //
            // 踩过这个坑：规则校验内部用**确定性正则**抽了一轮要素用于判定，
            // 但它**不落库**到 contract_element（那是 AI 抽取那条链路负责的）。
            // 结果演示时点开详情，"规则结论"有 4 条、"要素"却是 0 个——
            // 看起来像抽取功能坏了，其实是压根没调用。
            //
            // 在降级通道下这一步会抛 AiCallException，属预期，忽略即可：
            // 那时要素确实抽不出来，而详情页会如实显示为空并说明原因。
            String elemNote;
            try {
                var extraction = extractionService.extract(contractId);
                elemNote = "要素 " + extraction.aligned() + "/" + extraction.total();
            } catch (AiCallException e) {
                elemNote = "要素抽取降级（" + e.getCode() + "）";
            }

            String aiNote = "未执行 AI";
            if (runAi) {
                try {
                    var review = aiReviewService.review(contractId);
                    aiNote = "AI 候选 " + review.total() + " 条";
                } catch (AiCallException e) {
                    // 降级通道下属预期；演示仍可讲规则与要素
                    aiNote = "AI 降级（" + e.getCode() + "）";
                }
            }

            log.info("演示合同已就绪: id={} 《{}》 规则命中{} 通过{} 无法判定{}  {}  {}",
                    contractId, label, rule.hits(), rule.passes(), rule.undetermined(),
                    elemNote, aiNote);

        } catch (RuntimeException e) {
            log.warn("准备演示合同失败: {} - {}", label, e.toString());
        }
    }

    /**
     * 从应用自身的示例端点取 PDF。
     *
     * <p>为什么不直接 new 一个 PDF：那样要在种子代码里再维护一份 PDFBox 用法，
     * 而示例端点已经存在且经过测试。<b>复用已有的、被测试覆盖的代码路径。</b>
     */
    private byte[] fetchSample(int port, String template) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port
                            + "/api/debug/sample-contract.pdf?template=" + template))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.debug("取示例 PDF 失败: template={} {}", template, e.toString());
            return null;
        }
    }
}
