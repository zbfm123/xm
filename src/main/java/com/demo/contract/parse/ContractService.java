package com.demo.contract.parse;

import com.demo.contract.aireview.AiResultCache;
import com.demo.contract.auth.TenantContext;
import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.parse.domain.Contract;
import com.demo.contract.parse.domain.ContractStatus;
import com.demo.contract.parse.dto.ContractSummary;
import com.demo.contract.parse.dto.PageResult;
import com.demo.contract.parse.dto.UploadResult;
import com.demo.contract.parse.mapper.ContractMapper;
import com.demo.contract.parse.mapper.ContractTextMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * 合同的上传、查询与删除。
 *
 * <p>本类是实现 T-005（租户隔离）、T-006（上传幂等）、T-007（查询）、T-008（删除级联）的主体。
 *
 * <p>三条贯穿全类的纪律：
 * <ol>
 *   <li><b>租户 id 一律来自 {@link TenantContext#requireTenantId()}</b>，不接收调用方传入的租户参数——
 *       那样调用方也能"传错"。</li>
 *   <li><b>文件哈希在写库之前算</b>，先查是否已存在；幂等靠"查得到就复用"，
 *       而不是"先插入再捕获唯一键冲突"（后者会白白消耗自增 id）。</li>
 *   <li><b>失败必须整体失败</b>：不允许出现"有 contract 行但没有文件"或反之的半个状态。</li>
 * </ol>
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    /** 允许的扩展名。真实类型仍由魔数校验把关，这里只是第一道粗筛。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx");

    private final ContractMapper contractMapper;
    private final ContractTextMapper contractTextMapper;
    private final ContractFileStorage storage;
    private final AiResultCache aiResultCache;
    private final long maxSizeBytes;

    public ContractService(ContractMapper contractMapper,
                           ContractTextMapper contractTextMapper,
                           ContractFileStorage storage,
                           AiResultCache aiResultCache,
                           @Value("${app.upload.max-size-bytes}") long maxSizeBytes) {
        this.contractMapper = contractMapper;
        this.contractTextMapper = contractTextMapper;
        this.storage = storage;
        this.aiResultCache = aiResultCache;
        this.maxSizeBytes = maxSizeBytes;
    }

    // ==================================================================
    // 上传（T-006）
    // ==================================================================

    /**
     * 上传合同。
     *
     * <p>幂等语义：<b>同租户内，同一份文件字节只对应一条合同记录</b>。
     * 重复上传返回已有记录并置 {@code idempotent=true}，而不是报错——
     * 用户双击上传按钮是很常见的行为，报错反而莫名其妙。
     *
     * <p>注意校验顺序：先校验大小与扩展名，再算哈希。反过来的话，
     * 一个 1GB 的非法文件会先被完整读一遍算哈希，白费 IO。
     */
    @Transactional
    public UploadResult upload(MultipartFile file, String title) {
        Long tenantId = TenantContext.requireTenantId();

        validatePresence(file);
        validateExtension(file.getOriginalFilename());
        validateSize(file.getSize());

        byte[] bytes = readBytes(file);
        String fileHash = Digests.sha256Hex(bytes);

        // 幂等：同租户同文件已存在则直接复用
        Contract existing = contractMapper.findByFileHash(tenantId, fileHash);
        if (existing != null) {
            log.info("合同上传命中幂等: tenant={} contractId={} hash={}",
                    tenantId, existing.getId(), fileHash.substring(0, 12));
            return new UploadResult(ContractSummary.from(existing), true);
        }

        validateMagicBytes(bytes, file.getOriginalFilename());

        Contract contract = new Contract();
        contract.setTenantId(tenantId);
        contract.setTitle(resolveTitle(title, file.getOriginalFilename()));
        contract.setOriginalFilename(file.getOriginalFilename());
        contract.setFileHash(fileHash);
        contract.setFileSize((long) bytes.length);
        contract.setStatus(ContractStatus.UPLOADED);

        // 先落盘再写库：如果落盘失败，抛异常回滚事务，不会留下"库里有、磁盘没有"的记录
        String storagePath;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            storagePath = storage.save(tenantId, fileHash, in);
        } catch (IOException | ContractFileStorage.StorageException e) {
            throw new ContractException(ParseErrorCode.STORAGE_UNAVAILABLE,
                    "文件存储失败，请稍后重试", e);
        }
        contract.setStoragePath(storagePath);

        contractMapper.insert(contract);
        log.info("合同上传成功: tenant={} contractId={} size={}B",
                tenantId, contract.getId(), bytes.length);

        return new UploadResult(ContractSummary.from(contract), false);
    }

    // ==================================================================
    // 查询（T-007）
    // ==================================================================

    /**
     * 分页查询合同列表。
     *
     * @param keyword 标题或文件名关键字，可为 null
     * @param status  状态过滤字符串，可为 null；非法值直接报错而不是忽略
     * @param page    从 0 开始；负数按 0 处理
     * @param size    每页条数，被限制在 1~100 之间（防止 ?size=100000 拖垮数据库）
     */
    @Transactional(readOnly = true)
    public PageResult<ContractSummary> list(String keyword, String status, int page, int size) {
        Long tenantId = TenantContext.requireTenantId();

        ContractStatus statusFilter = (status == null || status.isBlank())
                ? null : ContractStatus.from(status);   // 非法值抛异常，不静默忽略

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;
        String normalizedKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        List<ContractSummary> items = contractMapper
                .findPage(tenantId, normalizedKeyword, statusFilter, safeSize, offset)
                .stream()
                .map(ContractSummary::from)
                .toList();

        long total = contractMapper.countPage(tenantId, normalizedKeyword, statusFilter);

        if (safePage > 0 && items.isEmpty()) {
            // 深分页越界：明确告知，避免前端以为"没有数据"
            log.debug("分页越界: tenant={} page={} total={}", tenantId, safePage, total);
        }

        return PageResult.of(items, total, safePage, safeSize);
    }

    /**
     * 查询合同详情。
     *
     * <p>租户不符时返回 404 而不是 403：<b>不确认"这份合同存在但不属于你"</b>，
     * 否则接口会变成合同 id 的探测器。
     */
    @Transactional(readOnly = true)
    public Contract get(Long id) {
        Long tenantId = TenantContext.requireTenantId();
        Contract contract = contractMapper.findByIdAndTenant(id, tenantId);
        if (contract == null) {
            throw new ContractException(ParseErrorCode.CONTRACT_NOT_FOUND, "合同不存在或无权访问");
        }
        return contract;
    }

    /** 读取合同原始文件流，供下载与解析使用。 */
    @Transactional(readOnly = true)
    public InputStream openFile(Long id) {
        Contract contract = get(id);
        if (!storage.exists(contract.getStoragePath())) {
            // 库里有记录但文件没了——这是数据不一致，必须显式报告而不是返回空流
            throw new ContractException(ParseErrorCode.STORAGE_UNAVAILABLE,
                    "合同文件丢失，请联系管理员");
        }
        return storage.open(contract.getStoragePath());
    }

    // ==================================================================
    // 删除（T-008）
    // ==================================================================

    /**
     * 删除合同：两段式清理。
     *
     * <p>这是整个 CRUD 里唯一需要动脑的一条，也是文档 D-13 记录的那个坑。
     *
     * <p><b>为什么要清 AI 缓存</b>：AI 结果按 {@code textHash} 缓存。
     * 若只删数据库行不清缓存，用户重新上传同一份合同会直接命中旧缓存，
     * 拿到<b>与本次无关的过期结论</b>——而且看起来完全正常。
     *
     * <p>两段式：
     * <ol>
     *   <li>立即：软删除数据库行（用户立刻看不到它）</li>
     *   <li>随后：物理清理磁盘文件、正文行、以及该 {@code textHash} 的 AI 缓存</li>
     * </ol>
     * 若第二步失败，软删除状态仍然成立，用户视角的删除已成功；
     * 缓存清理失败会导致下次上传命中旧缓存，因此这里对异常做日志告警而不是静默吞掉。
     */
    @Transactional
    public void delete(Long id) {
        Long tenantId = TenantContext.requireTenantId();

        Contract contract = contractMapper.findByIdAndTenant(id, tenantId);
        if (contract == null) {
            // 删除是幂等的：已经删掉的合同再删一次不算错误
            log.info("删除合同：不存在或已删除 tenant={} id={}", tenantId, id);
            return;
        }

        int affected = contractMapper.softDelete(id, tenantId);
        if (affected == 0) {
            throw new IllegalStateException("软删除失败，合同状态可能已被并发修改: id=" + id);
        }

        // ---- 第二段：物理清理 ----
        int textRows = contractTextMapper.deleteByContractId(id, tenantId);

        boolean fileRemoved = false;
        try {
            fileRemoved = storage.delete(contract.getStoragePath());
        } catch (ContractFileStorage.StorageException e) {
            // 磁盘删不掉不影响用户感知，但必须留痕，否则磁盘会被慢慢填满
            log.warn("合同文件删除失败，需人工清理: contractId={} path={}",
                    id, contract.getStoragePath(), e);
        }

        int cacheEvicted = 0;
        try {
            cacheEvicted = aiResultCache.evictByTextHash(contract.getTextHash());
        } catch (RuntimeException e) {
            // ⚠️ 这里不能吞：缓存没清掉，下次上传同一份合同会命中过期结论
            log.error("AI 缓存清理失败，下次上传同一份合同可能命中过期结论: contractId={} textHash={}",
                    id, contract.getTextHash(), e);
        }

        log.info("合同删除完成: tenant={} id={} 正文行={} 文件={} 缓存条数={}",
                tenantId, id, textRows, fileRemoved, cacheEvicted);
    }

    // ==================================================================
    // 内部：校验与工具
    // ==================================================================

    private void validatePresence(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ContractException(ParseErrorCode.EMPTY_FILE, "文件为空");
        }
    }

    private void validateSize(long size) {
        if (size > maxSizeBytes) {
            throw new ContractException(ParseErrorCode.FILE_TOO_LARGE,
                    "文件超过大小上限 " + (maxSizeBytes / 1024 / 1024) + "MB");
        }
    }

    private void validateExtension(String filename) {
        String ext = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ContractException(ParseErrorCode.MIME_MISMATCH,
                    "仅支持 " + ALLOWED_EXTENSIONS + " 格式，当前为: " + (ext.isEmpty() ? "无扩展名" : ext));
        }
    }

    /**
     * 魔数校验：不信任客户端给的 Content-Type 与扩展名。
     *
     * <p>PDF 以 {@code %PDF} 开头；DOCX 是 ZIP 容器，以 {@code PK\x03\x04} 开头。
     */
    private void validateMagicBytes(byte[] bytes, String filename) {
        String ext = extensionOf(filename);
        boolean ok;
        if ("pdf".equals(ext)) {
            ok = bytes.length >= 4
                    && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        } else {
            ok = bytes.length >= 4
                    && bytes[0] == 'P' && bytes[1] == 'K'
                    && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7);
        }
        if (!ok) {
            throw new ContractException(ParseErrorCode.MIME_MISMATCH,
                    "文件内容与扩展名 ." + ext + " 不符");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ContractException(ParseErrorCode.PARSE_FAILED, "读取上传文件失败", e);
        }
    }

    private String resolveTitle(String title, String filename) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        // 用文件名去掉扩展名作为默认标题
        if (filename == null || filename.isBlank()) {
            return "未命名合同";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    /** 供诊断使用：当前登录用户的租户。 */
    public Long currentTenant() {
        CurrentUser user = CurrentUser.require();
        return user.getTenantId();
    }
}
