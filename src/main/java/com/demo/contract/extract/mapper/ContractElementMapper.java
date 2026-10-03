package com.demo.contract.extract.mapper;

import com.demo.contract.extract.domain.ContractElementRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 要素持久化。
 *
 * <p>⚠️ 每条 SQL 都带 {@code tenant_id}（不变式 I-01），由架构测试在构建时校验。
 *
 * <p>重新抽取前先清旧行：要素是可重算的机器结论，不像人工复核那样需要留痕。
 * 唯一索引 {@code (contract_id, field_key)} 是第二道保险。
 */
@Mapper
public interface ContractElementMapper {

    @Insert("""
            INSERT INTO contract_element (tenant_id, contract_id, field_key, element_value, quote,
                                          char_start, char_end, confidence, match_level,
                                          status, status_reason, source)
            VALUES (#{tenantId}, #{contractId}, #{fieldKey}, #{elementValue}, #{quote},
                    #{charStart}, #{charEnd}, #{confidence}, #{matchLevel},
                    #{status}, #{statusReason}, #{source})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContractElementRow row);

    @Select("""
            SELECT id, tenant_id, contract_id, field_key, element_value, quote, char_start, char_end,
                   confidence, match_level, status, status_reason, source, created_at
              FROM contract_element
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
             ORDER BY field_key
            """)
    List<ContractElementRow> findByContract(@Param("contractId") Long contractId,
                                            @Param("tenantId") Long tenantId);

    @Delete("""
            DELETE FROM contract_element
             WHERE contract_id = #{contractId}
               AND tenant_id = #{tenantId}
            """)
    int deleteByContract(@Param("contractId") Long contractId, @Param("tenantId") Long tenantId);
}
