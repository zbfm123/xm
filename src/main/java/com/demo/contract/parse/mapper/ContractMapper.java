package com.demo.contract.parse.mapper;

import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

import java.util.List;

/**
 * 合同持久化。
 *
 * <p>⚠️ <b>本接口的每一条 SQL 都必须带 {@code tenant_id} 条件</b>（不变式 I-01）。
 * {@code TenantScopeArchitectureTest} 会在构建时扫描本接口的所有 SQL 并在缺失时报错——
 * 靠人记得写条件一定会漏，所以用测试锁住。
 *
 * <p>注意 {@code deleted = 0} 也出现在每个查询里：删除走软删除，忘带这个条件会让
 * 已删除的合同重新出现在列表里。
 */
@Mapper
public interface ContractMapper {

    @Insert("""
            INSERT INTO contract (tenant_id, title, original_filename, file_hash, storage_path,
                                  file_size, status, deleted)
            VALUES (#{tenantId}, #{title}, #{originalFilename}, #{fileHash}, #{storagePath},
                    #{fileSize}, #{status}, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Contract contract);

    /** 按 id + 租户查询。租户不符时返回 null，而不是"查到别人的合同"。 */
    @Select("""
            SELECT id, tenant_id, title, original_filename, file_hash, text_hash, storage_path,
                   file_size, status, deleted, created_at, updated_at
              FROM contract
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
               AND deleted = 0
            """)
    Contract findByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * 幂等查询：同租户下这个文件字节是否已上传过。
     *
     * <p>用文件字节哈希而不是文件名：同一份文件改名后重传应当命中已有记录。
     */
    @Select("""
            SELECT id, tenant_id, title, original_filename, file_hash, text_hash, storage_path,
                   file_size, status, deleted, created_at, updated_at
              FROM contract
             WHERE tenant_id = #{tenantId}
               AND file_hash = #{fileHash}
               AND deleted = 0
             LIMIT 1
            """)
    Contract findByFileHash(@Param("tenantId") Long tenantId, @Param("fileHash") String fileHash);

    /**
     * 列表查询：分页 + 关键字 + 状态筛选。
     *
     * @param keyword 匹配标题或原文件名，可为 null
     * @param status  状态过滤，可为 null 表示全部
     */
    @Select("""
            <script>
            SELECT id, tenant_id, title, original_filename, file_hash, text_hash, storage_path,
                   file_size, status, deleted, created_at, updated_at
              FROM contract
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
            <if test="keyword != null and keyword != ''">
               AND (title LIKE CONCAT('%', #{keyword}, '%')
                    OR original_filename LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null">
               AND status = #{status}
            </if>
             ORDER BY id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Contract> findPage(@Param("tenantId") Long tenantId,
                            @Param("keyword") String keyword,
                            @Param("status") ContractStatus status,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM contract
             WHERE tenant_id = #{tenantId}
               AND deleted = 0
            <if test="keyword != null and keyword != ''">
               AND (title LIKE CONCAT('%', #{keyword}, '%')
                    OR original_filename LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null">
               AND status = #{status}
            </if>
            </script>
            """)
    long countPage(@Param("tenantId") Long tenantId,
                   @Param("keyword") String keyword,
                   @Param("status") ContractStatus status);

    /** 更新状态。带租户条件，跨租户改状态会被数据库拒绝（影响行数 0）。 */
    @Update("""
            UPDATE contract
               SET status = #{status},
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
               AND deleted = 0
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("tenantId") Long tenantId,
                     @Param("status") ContractStatus status);

    /** 解析成功后回填文本哈希——AI 结果缓存以此为键。 */
    @Update("""
            UPDATE contract
               SET text_hash = #{textHash},
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
               AND deleted = 0
            """)
    int updateTextHash(@Param("id") Long id,
                       @Param("tenantId") Long tenantId,
                       @Param("textHash") String textHash);

    /**
     * 软删除。
     *
     * <p>为什么不直接 DELETE：审查结论与复核记录需要保留审计痕迹（不变式 I-06），
     * 物理删除会让历史结论失去归属主体。硬清理属于异步任务，见 T-008 的两段式设计。
     */
    @Update("""
            UPDATE contract
               SET deleted = 1,
                   status = 'DELETED',
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
               AND deleted = 0
            """)
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 物理删除，仅由异步清理任务调用（当前未实现，留作 T-008 后续）。 */
    @Delete("""
            DELETE FROM contract
             WHERE id = #{id}
               AND tenant_id = #{tenantId}
               AND deleted = 1
            """)
    int hardDelete(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
