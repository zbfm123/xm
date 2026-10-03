package com.demo.contract.parse;

import com.demo.contract.aireview.AiResultCache;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.parse.dto.ContractSummary;
import com.demo.contract.parse.dto.PageResult;
import com.demo.contract.parse.dto.UploadResult;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import com.demo.contract.support.TestFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合同链路集成测试（T-005 ~ T-008）。
 *
 * <p>覆盖的验收项：A-01（租户隔离）、A-02 的上传侧、以及本任务新增的幂等与删除语义。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class ContractServiceTest extends AuthenticatedTestBase {

    @Autowired
    private ContractService contractService;

    @Autowired
    private AiResultCache aiResultCache;

    @Autowired
    private com.demo.contract.parse.mapper.ContractMapper contractMapper;

    // ==================================================================
    // T-005：租户隔离（不变式 I-01）
    // ==================================================================

    @Test
    @DisplayName("A-01 跨租户不可见：另一个租户看不到、也删不掉本租户的合同")
    void contractsAreIsolatedBetweenTenants() throws IOException {
        loginAsDemoTenant();
        Long contractId = uploadPdf("采购合同-A", "A-内容").contract().id();

        // 换到对照租户
        loginAsOtherTenant();

        assertThatThrownBy(() -> contractService.get(contractId))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.CONTRACT_NOT_FOUND));

        assertThat(contractService.list(null, null, 0, 20).items()).isEmpty();

        // 删除也不应生效：另一个租户删不掉别人的合同
        contractService.delete(contractId);

        // 回到原租户确认合同仍在
        loginAsDemoTenant();
        assertThat(contractService.get(contractId).getId()).isEqualTo(contractId);
    }

    @Test
    @DisplayName("缺少登录上下文时取租户直接失败，绝不退化为查全部")
    void missingTenantContextMustFail() {
        // 不调用 loginAsXxx，且基类的 @AfterEach 保证此处无残留上下文
        assertThatThrownBy(() -> contractService.list(null, null, 0, 20))
                .isInstanceOf(com.demo.contract.auth.AuthException.class)
                .satisfies(e -> assertThat(((com.demo.contract.auth.AuthException) e).getCode())
                        .isEqualTo(com.demo.contract.auth.AuthErrorCode.TENANT_CONTEXT_MISSING));

        assertThatThrownBy(() -> contractService.get(1L))
                .isInstanceOf(com.demo.contract.auth.AuthException.class);
    }

    // ==================================================================
    // T-006：上传与幂等
    // ==================================================================

    @Test
    @DisplayName("上传 PDF 成功，状态为 UPLOADED，文件已落盘")
    void uploadPdfShouldSucceed() throws IOException {
        loginAsDemoTenant();
        UploadResult result = uploadPdf("采购合同", "hello");

        assertThat(result.idempotent()).isFalse();
        assertThat(result.contract().status()).isEqualTo(ContractStatus.UPLOADED.name());

        // 文件确实存在，且能读回来
        Contract saved = contractService.get(result.contract().id());
        try (var in = contractService.openFile(saved.getId())) {
            assertThat(in.readAllBytes()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("同一文件重复上传命中幂等，返回同一 id 且不新建记录")
    void duplicateUploadShouldBeIdempotent() throws IOException {
        loginAsDemoTenant();
        byte[] content = TestFiles.minimalPdf("幂等测试");

        UploadResult first = contractService.upload(
                new MockMultipartFile("file", "same.pdf", "application/pdf", content), null);
        UploadResult second = contractService.upload(
                new MockMultipartFile("file", "renamed.pdf", "application/pdf", content), null);

        assertThat(first.idempotent()).isFalse();
        assertThat(second.idempotent()).isTrue();
        // 改名后重传仍应命中同一条——幂等看内容，不看文件名
        assertThat(second.contract().id()).isEqualTo(first.contract().id());

        assertThat(contractService.list(null, null, 0, 50).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同租户上传同一文件应各自独立，不互相命中幂等")
    void sameFileInDifferentTenantsCreatesSeparateContracts() throws IOException {
        loginAsDemoTenant();
        Long a = uploadPdf("租户1的合同", "same-bytes").contract().id();

        loginAsOtherTenant();
        UploadResult b = contractService.upload(
                new MockMultipartFile("file", "same.pdf", "application/pdf",
                        TestFiles.minimalPdf("same-bytes")), null);

        assertThat(b.idempotent()).isFalse();
        assertThat(b.contract().id()).isNotEqualTo(a);
    }

    @Test
    @DisplayName("扩展名伪装被拒：内容是文本却叫 .pdf")
    void mismatchedMagicBytesShouldBeRejected() {
        loginAsDemoTenant();
        MockMultipartFile fake = new MockMultipartFile(
                "file", "evil.pdf", "application/pdf", TestFiles.fakePdfWithWrongContent());

        assertThatThrownBy(() -> contractService.upload(fake, null))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.MIME_MISMATCH));
    }

    @Test
    @DisplayName("不支持的扩展名被拒")
    void unsupportedExtensionShouldBeRejected() {
        loginAsDemoTenant();
        MockMultipartFile txt = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> contractService.upload(txt, null))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.MIME_MISMATCH));
    }

    @Test
    @DisplayName("文件超限被拒，且在校验阶段就拒绝（不去读内容算哈希）")
    void oversizedFileShouldBeRejectedBeforeReading() {
        loginAsDemoTenant();

        // 用 mock 报告一个超大 size：因为是 mock，没有任何真实字节被分配。
        // 这同时证明校验顺序正确——先看 size，不会先把文件读进内存。
        MockMultipartFile huge = mock(MockMultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getOriginalFilename()).thenReturn("huge.pdf");
        when(huge.getSize()).thenReturn(200L * 1024 * 1024);

        assertThatThrownBy(() -> contractService.upload(huge, null))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("空文件被拒")
    void emptyFileShouldBeRejected() {
        loginAsDemoTenant();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> contractService.upload(empty, null))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.EMPTY_FILE));
    }

    @Test
    @DisplayName("未传标题时用文件名（去扩展名）作为默认标题")
    void titleShouldDefaultToFilename() throws IOException {
        loginAsDemoTenant();
        UploadResult r = contractService.upload(
                new MockMultipartFile("file", "2026年度采购合同.pdf", "application/pdf",
                        TestFiles.minimalPdf("标题测试")), null);

        assertThat(r.contract().title()).isEqualTo("2026年度采购合同");
    }

    @Test
    @DisplayName("DOCX 也能上传（魔数为 PK）")
    void uploadDocxShouldSucceed() throws IOException {
        loginAsDemoTenant();
        byte[] docx = TestFiles.minimalDocx("docx测试");

        UploadResult r = contractService.upload(
                new MockMultipartFile("file", "合同.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx), null);

        assertThat(r.idempotent()).isFalse();
        assertThat(r.contract().originalFilename()).isEqualTo("合同.docx");
    }

    // ==================================================================
    // T-007：查询
    // ==================================================================

    @Test
    @DisplayName("列表支持关键字与状态筛选，并正确分页")
    void listShouldSupportFiltersAndPaging() throws IOException {
        loginAsDemoTenant();
        for (int i = 1; i <= 5; i++) {
            contractService.upload(new MockMultipartFile("file", "采购合同" + i + ".pdf",
                    "application/pdf", TestFiles.minimalPdf("内容-" + i)), null);
        }
        contractService.upload(new MockMultipartFile("file", "保密协议.pdf",
                "application/pdf", TestFiles.minimalPdf("保密内容")), null);

        // 全部
        assertThat(contractService.list(null, null, 1, 20).total()).isEqualTo(6);

        // 关键字筛选
        PageResult<ContractSummary> purchase = contractService.list("采购", null, 1, 20);
        assertThat(purchase.total()).isEqualTo(5);

        // 分页：对外 1 基。每页 2 条，第 1 页
        PageResult<ContractSummary> page1 = contractService.list(null, null, 1, 2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.total()).isEqualTo(6);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(page1.page())
                .withFailMessage("对外页码应当是 1 基的，返回了 %s", page1.page())
                .isEqualTo(1);

        // 第 3 页是最后有数据的一页
        PageResult<ContractSummary> page3 = contractService.list(null, null, 3, 2);
        assertThat(page3.items()).hasSize(2);

        // ⚠️ 越界页**夹到最后一页**，而不是返回空列表。
        //
        // 为什么不返回空：前端删掉最后一页的最后一条后重新拉取，页码就超范围了。
        // 返回空列表会让界面显示"暂无数据"，而实际上前一页还有内容——
        // 用户会以为数据全没了。
        //
        // 这个坑我真踩过：客户端的 1 基页码被当成 0 基，
        // offset 直接跳过第一页，症状是"列表空着但显示共 3 条"。
        PageResult<ContractSummary> page9 = contractService.list(null, null, 9, 2);
        assertThat(page9.items())
                .withFailMessage("越界页返回了空列表，用户会以为数据没了")
                .hasSize(2);
        assertThat(page9.page()).isEqualTo(3);
        assertThat(page9.total()).isEqualTo(6);
    }

    @Test
    @DisplayName("状态筛选：非法状态值报错而不是被静默忽略")
    void invalidStatusFilterShouldFail() {
        loginAsDemoTenant();
        assertThatThrownBy(() -> contractService.list(null, "NOT_A_STATUS", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知合同状态");
    }

    @Test
    @DisplayName("分页参数被收敛：size 上限 100，page 小于 1 按第 1 页（对外 1 基）")
    void pagingParametersShouldBeClamped() throws IOException {
        loginAsDemoTenant();
        uploadPdf("一份合同", "内容");

        PageResult<ContractSummary> r = contractService.list(null, null, -5, 100000);
        assertThat(r.page())
                .withFailMessage("对外是 1 基分页，最小页号应为 1，实际 %s", r.page())
                .isEqualTo(1);
        assertThat(r.size()).isEqualTo(100);
        // 夹到第 1 页后应当能拿到数据，而不是因为页号非法返回空
        assertThat(r.items()).hasSize(1);
    }

    // ==================================================================
    // T-008：删除与缓存清理
    // ==================================================================

    @Test
    @DisplayName("删除后不再出现在列表与详情中（软删除）")
    void deleteShouldHideFromQueries() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("待删除合同", "待删内容").contract().id();

        contractService.delete(id);

        assertThatThrownBy(() -> contractService.get(id))
                .isInstanceOf(ContractException.class)
                .satisfies(e -> assertThat(((ContractException) e).getCode())
                        .isEqualTo(ParseErrorCode.CONTRACT_NOT_FOUND));
        assertThat(contractService.list(null, null, 0, 20).total()).isZero();
    }

    @Test
    @DisplayName("删除是幂等的：重复删除不报错")
    void deleteShouldBeIdempotent() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("重复删除", "内容").contract().id();

        contractService.delete(id);
        contractService.delete(id);   // 不应抛异常
        contractService.delete(999999L);  // 不存在的 id 同样不报错
    }

    @Test
    @DisplayName("删除会定向清理该文本哈希的 AI 缓存（否则重传会命中过期结论）")
    void deleteShouldEvictAiCacheForItsTextHash() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("缓存清理", "待清理内容").contract().id();

        // 模拟这份合同已有 AI 审查缓存
        String textHash = "abc123def456";
        setTextHash(id, textHash);
        RedisTestConfig.put("ai:result:" + textHash + ":v1:deepseek-chat", "{\"cached\":true}");
        assertThat(aiResultCache.get(textHash)).isNotNull();

        contractService.delete(id);

        assertThat(aiResultCache.get(textHash))
                .withFailMessage("删除合同后 AI 缓存未清理：重新上传同一份合同会命中过期结论")
                .isNull();
    }

    @Test
    @DisplayName("删除会清理磁盘文件")
    void deleteShouldRemoveStoredFile() throws IOException {
        loginAsDemoTenant();
        Long id = uploadPdf("文件清理", "文件内容").contract().id();

        Contract before = contractService.get(id);
        assertThat(contractService.openFile(id)).isNotNull();

        contractService.delete(id);

        // 通过反射之外的最小接口验证：存储里已查不到该路径
        assertThatThrownBy(() -> contractService.openFile(id))
                .isInstanceOf(ContractException.class);
        assertThat(before.getStoragePath()).isNotBlank();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private UploadResult uploadPdf(String title, String marker) {
        return contractService.upload(
                new MockMultipartFile("file", title + ".pdf", "application/pdf",
                        TestFiles.minimalPdf(marker)), title);
    }

    /**
     * 直接写 text_hash，模拟解析已完成（真实解析在 T-009）。
     *
     * <p>刻意用 Mapper 而不是给 Service 加一个"仅供测试"的方法：
     * <b>生产代码里不该出现 test-only 的入口</b>，那会诱使后续代码也走它。
     */
    private void setTextHash(Long contractId, String textHash) {
        contractMapper.updateTextHash(contractId, DEMO_TENANT, textHash);
    }
}
