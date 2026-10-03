package com.demo.contract.review.mapper;

import com.demo.contract.review.domain.ReviewActionRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 人工复核动作持久化。
 *
 * <p>⚠️ <b>本接口刻意只有 insert 与 select，没有 update、没有 delete。</b>
 *
 * <p>这不是"暂时没写"——不变式 I-06 要求复核痕迹只追加。
 * 「不提供修改入口」比「约定不要修改」可靠得多：
 * 后者需要每个后来者都知道并遵守，前者让人想违反也找不到方法。
 *
 * <p>{@code AppendOnlyAuditContractTest} 会反射扫描本接口，
 * 一旦有人加了 update/delete 方法，构建就会失败。
 *
 * <h3>⚠️ 为什么读取都要挂 {@link ReviewActionRowTypeHandler}</h3>
 *
 * <p>这是踩过的坑。{@link ReviewActionRow} 是刻意设计的不可变类，
 * <b>只有一个构造函数</b>。MyBatis 遇到"只有一个构造函数"的返回类型时，
 * 会启用<b>按列顺序的构造器自动映射</b>
 * （{@code applyColumnOrderBasedConstructorAutomapping}），
 * 把第 N 列塞给第 N 个构造参数。
 *
 * <p>症状极具误导性：报错说
 * {@code Error attempting to get column 'reason'}，
 * 读它的却是 {@code LongTypeHandler}——
 * 因为 MyBatis 正拿 {@code reason}（字符串）去填构造参数里的 {@code Long}。
 * <b>看起来像中文编码问题，实际是列顺序与构造参数顺序不一致。</b>
 *
 * <p>我先试过写完整的 {@code @Results}，但自动映射依然被触发。
 * 与其继续和自动推断的规则博弈，不如用 {@code @ResultType} 指定一个
 * <b>按列名手写映射</b>的 TypeHandler：一个地方写清楚，不依赖任何隐式推断。
 *
 * <p>另一个坑：列名 {@code action} 在 H2 里是保留字（同 {@code value}），
 * 因此建表用 {@code action_code}，读取时用 {@code action_code AS action}。
 *
 * <p>每条 SQL 都带 {@code tenant_id}（不变式 I-01），由架构测试校验。
 */
@Mapper
public interface ReviewActionMapper {

    @Insert("""
            INSERT INTO review_action (tenant_id, contract_id, finding_id, idempotency_key,
                                       action_code, reason, operator_id, operator_name,
                                       previous_status, new_status, record_hash, previous_hash)
            VALUES (#{tenantId}, #{contractId}, #{findingId}, #{idempotencyKey},
                    #{action}, #{reason}, #{operatorId}, #{operatorName},
                    #{previousStatus}, #{newStatus}, #{recordHash}, #{previousHash})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewActionRow row);

    /**
     * 按 id 升序读取某合同的全部复核记录。
     *
     * <p><b>必须按 id 升序</b>：哈希链的校验依赖顺序，顺序错了校验必然失败。
     */
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT id, tenant_id, contract_id, finding_id, idempotency_key,
                   action_code AS action, reason, operator_id, operator_name,
                   previous_status, new_status, record_hash, previous_hash, created_at
              FROM review_action
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
             ORDER BY id
            """)
    List<ReviewActionRow> findByContract(@Param("contractId") Long contractId,
                                         @Param("tenantId") Long tenantId);

    /** 取链尾（最新一条），用于计算下一条的 previous_hash。 */
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT id, tenant_id, contract_id, finding_id, idempotency_key,
                   action_code AS action, reason, operator_id, operator_name,
                   previous_status, new_status, record_hash, previous_hash, created_at
              FROM review_action
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
             ORDER BY id DESC
             LIMIT 1
            """)
    ReviewActionRow findLast(@Param("contractId") Long contractId,
                             @Param("tenantId") Long tenantId);

    /** 幂等查询：同一幂等键不应产生第二条记录。 */
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT id, tenant_id, contract_id, finding_id, idempotency_key,
                   action_code AS action, reason, operator_id, operator_name,
                   previous_status, new_status, record_hash, previous_hash, created_at
              FROM review_action
             WHERE tenant_id = #{tenantId}
               AND idempotency_key = #{idempotencyKey}
            """)
    ReviewActionRow findByIdempotencyKey(@Param("tenantId") Long tenantId,
                                         @Param("idempotencyKey") String idempotencyKey);

    /** 读取某条 AI 结论的复核历史（只读）。 */
    @org.apache.ibatis.annotations.Options(flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT id, tenant_id, contract_id, finding_id, idempotency_key,
                   action_code AS action, reason, operator_id, operator_name,
                   previous_status, new_status, record_hash, previous_hash, created_at
              FROM review_action
             WHERE finding_id = #{findingId}
               AND tenant_id = #{tenantId}
             ORDER BY id
            """)
    List<ReviewActionRow> findByFinding(@Param("findingId") Long findingId,
                                        @Param("tenantId") Long tenantId);
}
