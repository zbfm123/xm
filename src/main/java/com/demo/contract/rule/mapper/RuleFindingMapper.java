package com.demo.contract.rule.mapper;

import com.demo.contract.rule.domain.RuleFindingEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 规则结论持久化。
 *
 * <p>⚠️ 每条 SQL 都带 {@code tenant_id}（不变式 I-01），
 * 由 {@code TenantScopeArchitectureTest} 在构建时校验。
 *
 * <p>重新校验时先按合同删除旧结论再写入新结论：<b>规则集变化后，
 * 保留上一轮的结论会让报告混入已经不存在的判断。</b>
 * 这与"复核记录只追加"不同——那是人工痕迹，这是可重算的机器结论。
 */
@Mapper
public interface RuleFindingMapper {

    @Insert("""
            INSERT INTO rule_finding (tenant_id, contract_id, rule_code, rule_name, severity,
                                      result, evidence, char_start, char_end, detail, error_message)
            VALUES (#{tenantId}, #{contractId}, #{ruleCode}, #{ruleName}, #{severity},
                    #{result}, #{evidence}, #{charStart}, #{charEnd}, #{detail}, #{errorMessage})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RuleFindingEntity finding);

    @Select("""
            SELECT id, tenant_id, contract_id, rule_code, rule_name, severity,
                   result, evidence, char_start, char_end, detail, error_message, checked_at
              FROM rule_finding
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
             ORDER BY rule_code
            """)
    List<RuleFindingEntity> findByContract(@Param("contractId") Long contractId,
                                           @Param("tenantId") Long tenantId);

    /** 只取命中的结论，报告正文用。 */
    @Select("""
            SELECT id, tenant_id, contract_id, rule_code, rule_name, severity,
                   result, evidence, char_start, char_end, detail, error_message, checked_at
              FROM rule_finding
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
               AND result = 'HIT'
             ORDER BY severity, rule_code
            """)
    List<RuleFindingEntity> findHits(@Param("contractId") Long contractId,
                                     @Param("tenantId") Long tenantId);

    /** 重新校验前清空旧结论。级联删除时也复用本方法。 */
    @Delete("""
            DELETE FROM rule_finding
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
            """)
    int deleteByContract(@Param("contractId") Long contractId, @Param("tenantId") Long tenantId);
}
