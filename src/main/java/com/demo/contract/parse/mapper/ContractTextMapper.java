package com.demo.contract.parse.mapper;

import com.demo.contract.parse.domain.ContractText;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 合同正文持久化。
 *
 * <p>⚠️ 同样受不变式 I-01 约束：每条 SQL 都带 {@code tenant_id}，
 * 由 {@code TenantScopeArchitectureTest} 在构建时校验。
 */
@Mapper
public interface ContractTextMapper {

    @Insert("""
            INSERT INTO contract_text (tenant_id, contract_id, text, original_text, text_hash,
                                       offset_map, no_extractable_text)
            VALUES (#{tenantId}, #{contractId}, #{text}, #{originalText}, #{textHash},
                    #{offsetMap}, #{noExtractableText})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContractText contractText);

    @Select("""
            SELECT id, tenant_id, contract_id, text, original_text, text_hash, offset_map,
                   no_extractable_text, created_at
              FROM contract_text
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
            """)
    ContractText findByContractId(@Param("contractId") Long contractId, @Param("tenantId") Long tenantId);

    /** 级联清理用：删除某合同的正文。返回影响行数便于断言。 */
    @Delete("""
            DELETE FROM contract_text
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
            """)
    int deleteByContractId(@Param("contractId") Long contractId, @Param("tenantId") Long tenantId);
}
