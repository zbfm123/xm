package com.demo.contract.review.mapper;

import com.demo.contract.review.domain.ReviewTaskRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审查任务持久化。
 *
 * <p>⚠️ 每条 SQL 都带 {@code tenant_id}（不变式 I-01），由架构测试在构建时校验。
 *
 * <p>与 {@code review_action} 不同，任务表<b>允许更新</b>——
 * 它记录的是"流程当前走到哪里"，而不是"发生过什么"。
 * 这两者的区别很重要：把流程当前态塞进只追加表，
 * 就会变成"每推进一次状态就追加一条"，读当前状态要扫全表。
 */
@Mapper
public interface ReviewTaskMapper {

    @Insert("""
            INSERT INTO review_task (tenant_id, contract_id, idempotency_key, status,
                                     status_reason, ai_available, total_findings,
                                     reviewed_count, created_by)
            VALUES (#{tenantId}, #{contractId}, #{idempotencyKey}, #{status},
                    #{statusReason}, #{aiAvailable}, #{totalFindings},
                    #{reviewedCount}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewTaskRow row);

    @Select("""
            SELECT id, tenant_id, contract_id, idempotency_key, status, status_reason,
                   ai_available, total_findings, reviewed_count, created_by,
                   created_at, updated_at
              FROM review_task
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
            """)
    ReviewTaskRow findById(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * 幂等查询：同一个键只对应一个任务。
     *
     * <p>唯一索引 {@code uk_review_task_idem} 是第二道保险——
     * 并发下两个请求可能同时通过这次查询，那时靠唯一索引兜住。
     */
    @Select("""
            SELECT id, tenant_id, contract_id, idempotency_key, status, status_reason,
                   ai_available, total_findings, reviewed_count, created_by,
                   created_at, updated_at
              FROM review_task
             WHERE tenant_id = #{tenantId}
               AND idempotency_key = #{idempotencyKey}
            """)
    ReviewTaskRow findByIdempotencyKey(@Param("tenantId") Long tenantId,
                                       @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT id, tenant_id, contract_id, idempotency_key, status, status_reason,
                   ai_available, total_findings, reviewed_count, created_by,
                   created_at, updated_at
              FROM review_task
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
             ORDER BY id DESC
            """)
    List<ReviewTaskRow> findByContract(@Param("contractId") Long contractId,
                                       @Param("tenantId") Long tenantId);

    /**
     * 更新状态与统计。
     *
     * <p>WHERE 里同时带 {@code tenant_id} 与 {@code id}：
     * 前者保证租户隔离，后者保证不会误改别的任务。
     */
    @Update("""
            UPDATE review_task
               SET status = #{status},
                   status_reason = #{statusReason},
                   ai_available = #{aiAvailable},
                   total_findings = #{totalFindings},
                   reviewed_count = #{reviewedCount},
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
            """)
    int updateState(@Param("id") Long id,
                    @Param("tenantId") Long tenantId,
                    @Param("status") String status,
                    @Param("statusReason") String statusReason,
                    @Param("aiAvailable") boolean aiAvailable,
                    @Param("totalFindings") int totalFindings,
                    @Param("reviewedCount") int reviewedCount);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM review_task
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
            """)
    int deleteByContract(@Param("contractId") Long contractId, @Param("tenantId") Long tenantId);
}
