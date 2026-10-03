package com.demo.contract.aireview;

import com.demo.contract.extract.ElementExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 AI 调用放进**独立事务**执行。
 *
 * <p><b>这个类解决一个非常隐蔽的 bug，值得完整记下来。</b>
 *
 * <h3>症状</h3>
 * 单元测试里降级路径工作正常，但通过真实 HTTP 调用时任务启动失败，
 * 返回 500，日志里是：
 * <pre>
 *   UnexpectedRollbackException: Transaction rolled back because it
 *   has been marked as rollback-only
 * </pre>
 *
 * <h3>根因</h3>
 * {@code ElementExtractionService.extract} 与 {@code AiReviewService.review}
 * 各自带 {@code @Transactional}。它们抛 {@code AiCallException} 时，
 * Spring 把<b>当前事务</b>标记为 rollback-only。
 *
 * <p>调用方即使 {@code catch} 住了异常并继续，**提交时依然会整体回滚**——
 * 因为标记一旦打上就不会撤销。于是：
 * <ul>
 *   <li>规则校验白跑了</li>
 *   <li>已经写入的 {@code review_task} 记录被回滚</li>
 *   <li>客户端拿到 500，而不是"已降级"</li>
 * </ul>
 *
 * <p><b>最要命的是：单元测试里看不出来。</b> 测试方法自身的
 * {@code @Transactional} 让一切都在一个大事务里，回滚的边界被测试框架掩盖了。
 * 只有真实 HTTP 调用（每个请求一个事务）才会暴露。
 *
 * <h3>修法</h3>
 * 把 AI 调用包在 {@link Propagation#REQUIRES_NEW} 里：
 * 内层是一个**独立事务**，它回滚不会污染外层。
 * 外层随后照常提交"任务已降级"这个事实。
 *
 * <p><b>语义上也更正确</b>：AI 调用的失败是一个独立事件，
 * 不该把"任务已创建"这件事一起否定掉。任务确实创建了，只是降级了。
 *
 * <p>⚠️ 注意：这个方法是 {@code public} 并且在**另一个 bean** 上，
 * 这样 Spring 的代理才会生效。写在同一个类里自调用是不行的——
 * 那是事务注解最常见的失效原因。
 */
@Component
public class AiInvocationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiInvocationRunner.class);

    private final ElementExtractionService extractionService;
    private final AiReviewService aiReviewService;

    public AiInvocationRunner(ElementExtractionService extractionService,
                              AiReviewService aiReviewService) {
        this.extractionService = extractionService;
        this.aiReviewService = aiReviewService;
    }

    /**
     * 在独立事务中执行要素抽取与 AI 审查。
     *
     * <p>任一步抛异常都会让这个独立事务回滚，
     * <b>但不会影响调用方所在的事务</b>。
     *
     * @return 审查产出的结论条数
     * @throws com.demo.contract.aireview.client.AiCallException AI 通道不可用或调用失败
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int runExtractionAndReview(Long contractId) {
        extractionService.extract(contractId);
        var review = aiReviewService.review(contractId);
        log.debug("AI 调用独立事务完成: contractId={} 结论={} 条", contractId, review.total());
        return review.total();
    }
}
