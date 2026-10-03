package com.demo.contract.parse;

import com.demo.contract.auth.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘文件存储（决策 D-09：不用 MinIO）。
 *
 * <p>路径规则：{@code <root>/<tenantId>/<前两位>/<fileHash>}。
 * 按租户分目录有两个好处：一是人工排查时能直接看出归属，
 * 二是将来做租户级清理（例如注销）时可以整目录删除。
 *
 * <p>文件名用内容哈希，因此同一份文件重复上传天然去重——
 * <b>幂等的实现是"结构上不可能重复"，而不是"用 if 判断一下"。</b>
 *
 * <p>抽象成接口是为了将来能换成 OSS：调用方只依赖本接口，
 * 更换实现时不需要改业务代码。
 */
@Component
public class ContractFileStorage {

    private static final Logger log = LoggerFactory.getLogger(ContractFileStorage.class);

    private final Path root;

    public ContractFileStorage(@Value("${app.storage.root}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + this.root, e);
        }
        log.info("文件存储根目录: {}", this.root);
    }

    /**
     * 保存文件。
     *
     * @return 相对于根目录的路径，写入 {@code contract.storage_path}
     * @throws StorageException 磁盘不可写时抛出，调用方须回滚，不得留下"半个合同"
     */
    public String save(Long tenantId, String fileHash, InputStream content) {
        String relative = relativePath(tenantId, fileHash);
        Path target = root.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target) && Files.size(target) > 0) {
                // 内容寻址：同哈希即同内容，无需重写
                return relative;
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            // 先写临时文件再原子移动：避免进程中断留下一个看似完整的半截文件
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return relative;
        } catch (IOException e) {
            throw new StorageException("文件写入失败: " + relative, e);
        }
    }

    public InputStream open(String relativePath) {
        try {
            return Files.newInputStream(root.resolve(relativePath));
        } catch (IOException e) {
            throw new StorageException("文件读取失败: " + relativePath, e);
        }
    }

    public boolean exists(String relativePath) {
        return relativePath != null && Files.exists(root.resolve(relativePath));
    }

    /**
     * 删除文件。
     *
     * @return 是否确实删除了文件（文件本就不存在时返回 false，不报错——删除是幂等的）
     */
    public boolean delete(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(root.resolve(relativePath));
        } catch (IOException e) {
            throw new StorageException("文件删除失败: " + relativePath, e);
        }
    }

    /** 存储根目录，供诊断端点与测试使用。 */
    public Path rootPath() {
        return root;
    }

    private String relativePath(Long tenantId, String fileHash) {
        // 分片取哈希前两位，避免单目录下文件过多
        return tenantId + "/" + fileHash.substring(0, 2) + "/" + fileHash;
    }

    /** 存储层异常：必须让上层显式失败，而不是被吞成"上传成功但文件不在"。 */
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
