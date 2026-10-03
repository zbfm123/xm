package com.demo.contract.aireview;

import com.demo.contract.aireview.mapper.AiFindingMapper;
import com.demo.contract.extract.ElementExtractionService;
import com.demo.contract.extract.mapper.ContractElementMapper;
import com.demo.contract.parse.ContractParsingService;
import com.demo.contract.parse.ContractService;
import com.demo.contract.parse.TestPdfFactory;
import com.demo.contract.support.AuthenticatedTestBase;
import com.demo.contract.support.RedisTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除合同时的级联清理测试。
 *
 * <p><b>为什么单独建一个测试类</b>：孤儿数据不报错、不影响任何功能，
 * 只会在统计与存储上慢慢积累。这类问题靠人工发现几乎不可能，
 * 必须由测试锁住"新增机器结论表就要登记到清理段"这条纪律。
 *
 * <p>我踩过这个坑：建了 {@code contract_element} 之后忘了加进清理段，
 * 删除合同后要素行留在库里。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class CascadeCleanupTest extends AuthenticatedTestBase {

    @Autowired private ContractService contractService;
    @Autowired private ContractParsingService parsingService;
    @Autowired private ElementExtractionService extractionService;
    @Autowired private AiReviewService aiReviewService;
    @Autowired private ContractElementMapper elementMapper;
    @Autowired private AiFindingMapper aiFindingMapper;
    @Autowired private TestPdfFactory pdfFactory;

    @Test
    @DisplayName("删除合同后：要素行与 AI 结论行都必须是 0（不留孤儿数据）")
    void deleteShouldCascadeToElementsAndFindings() throws Exception {
        loginAsDemoTenant();

        String text = String.join("\n",
                "服务合同（虚构样例）",
                "甲方：北京某某科技有限公司",
                "乙方：上海某某贸易有限公司",
                "付款方式：另行约定。",
                "因本合同产生的一切损失均由乙方承担全部责任。",
                "本合同期满后自动续约一年。");

        byte[] pdf = pdfFactory.build(text.lines().toList());
        Long id = contractService.upload(new MockMultipartFile(
                "file", "c.pdf", "application/pdf", pdf), "级联清理").contract().id();
        parsingService.parse(id);

        // 先确认两类数据都真的写进去了，否则"删除后为 0"可能是假绿
        var extraction = extractionService.extract(id);
        var review = aiReviewService.review(id);
        Long tenantId = com.demo.contract.auth.TenantContext.requireTenantId();

        assertThat(extraction.total())
                .withFailMessage("要素没抽取成功，本测试会变成假绿")
                .isGreaterThan(0);
        assertThat(elementMapper.findByContract(id, tenantId)).isNotEmpty();
        assertThat(review.total())
                .withFailMessage("AI 审查没有产出，本测试会变成假绿")
                .isGreaterThan(0);
        assertThat(aiFindingMapper.findByContract(id, tenantId)).isNotEmpty();

        // 执行删除
        contractService.delete(id);

        assertThat(elementMapper.findByContract(id, tenantId))
                .withFailMessage("删除合同后 contract_element 仍有孤儿数据——"
                        + "新增机器结论表时必须登记到 ContractService 的清理段")
                .isEmpty();
        assertThat(aiFindingMapper.findByContract(id, tenantId))
                .withFailMessage("删除合同后 ai_finding 仍有孤儿数据")
                .isEmpty();
    }

    @Test
    @DisplayName("级联清理不影响其他合同的数据")
    void cascadeShouldNotTouchOtherContracts() throws Exception {
        loginAsDemoTenant();

        // ⚠️ 两份合同的正文必须不同：上传是按内容哈希幂等的，
        // 内容相同会返回同一份合同，测试就退化成"只有一份合同"而失去意义。
        byte[] keepPdf = pdfFactory.build(java.util.List.of(
                "采购合同（保留）", "甲方：甲公司", "乙方：乙公司",
                "付款方式：另行约定。", "本合同期满后自动续约一年。"));
        byte[] dropPdf = pdfFactory.build(java.util.List.of(
                "服务合同（删除）", "甲方：丙公司", "乙方：丁公司",
                "付款方式：见附件。", "因本合同产生的一切损失均由乙方承担全部责任。"));

        Long keep = contractService.upload(new MockMultipartFile(
                "file", "k.pdf", "application/pdf", keepPdf), "保留").contract().id();
        Long drop = contractService.upload(new MockMultipartFile(
                "file", "d.pdf", "application/pdf", dropPdf), "删除").contract().id();

        assertThat(drop)
                .withFailMessage("两份合同内容相同被幂等合并，本测试无法验证隔离性")
                .isNotEqualTo(keep);

        parsingService.parse(keep);
        parsingService.parse(drop);

        extractionService.extract(keep);
        extractionService.extract(drop);

        Long tenantId = com.demo.contract.auth.TenantContext.requireTenantId();
        int keepCount = elementMapper.findByContract(keep, tenantId).size();
        assertThat(keepCount).isGreaterThan(0);

        contractService.delete(drop);

        assertThat(elementMapper.findByContract(drop, tenantId)).isEmpty();
        assertThat(elementMapper.findByContract(keep, tenantId))
                .withFailMessage("清理误伤了其他合同的数据")
                .hasSize(keepCount);
    }
}
