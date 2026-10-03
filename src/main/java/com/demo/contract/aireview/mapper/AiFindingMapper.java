package com.demo.contract.aireview.mapper;

import com.demo.contract.aireview.domain.AiFindingRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 风险结论持久化。
 *
 * <p>⚠️ 每条 SQL 都带 {@code tenant_id}（不变式 I-01），由架构测试在构建时校验。
 *
 * <p>重新审查时先清旧行——与规则结论同理，机器结论可重算。
 * <b>人工复核痕迹不在这里</b>，那是 T-018 的只追加表。
 */
@Mapper
public interface AiFindingMapper {

    @Insert("""
            INSERT INTO ai_finding (tenant_id, contract_id, risk_type, quote, char_start, char_end,
                                    confidence, match_level, status, status_reason,
                                    model_version, prompt_version)
            VALUES (#{tenantId}, #{contractId}, #{riskType}, #{quote}, #{charStart}, #{charEnd},
                    #{confidence}, #{matchLevel}, #{status}, #{statusReason},
                    #{modelVersion}, #{promptVersion})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiFindingRow row);

    @Select("""
            SELECT id, tenant_id, contract_id, risk_type, quote, char_start, char_end,
                   confidence, match_level, status, status_reason,
                   model_version, prompt_version, created_at
              FROM ai_finding
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
             ORDER BY confidence DESC, id
            """)
    List<AiFindingRow> findByContract(@Param("contractId") Long contractId,
                                      @Param("tenantId") Long tenantId);

    /**
     * 只取可进入报告正文的结论。
     *
     * <p>"可进入"= 证据已定位（{@code char_start} 非空）。
     * <b>对齐失败的条目退化成候选人工项，不进报告正文</b>——这是不变式 I-02 的落点。
     */
    @Select("""
            SELECT id, tenant_id, contract_id, risk_type, quote, char_start, char_end,
                   confidence, match_level, status, status_reason,
                   model_version, prompt_version, created_at
              FROM ai_finding
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
               AND char_start IS NOT NULL
             ORDER BY confidence DESC, id
            """)
    List<AiFindingRow> findReportable(@Param("contractId") Long contractId,
                                      @Param("tenantId") Long tenantId);

    @Delete("""
            DELETE FROM ai_finding
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
            """)
    int deleteByContract(@Param("contractId") Long contractId, @Param("tenantId") Long tenantId);
}
