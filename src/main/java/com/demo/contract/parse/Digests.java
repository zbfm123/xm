package com.demo.contract.parse;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 工具。
 *
 * <p>{@link #sha256Hex(byte[])} 用于小数据（文本哈希）；
 * {@link #sha256Hex(InputStream)} 用于上传文件，边读边算，<b>不需要把整个文件读进内存</b>。
 */
public final class Digests {

    private Digests() {
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 流式计算文件哈希。
     *
     * @param in     调用方负责关闭
     */
    public static String sha256Hex(InputStream in) {
        try {
            MessageDigest digest = newDigest();
            try (DigestInputStream dis = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[8192];
                while (dis.read(buffer) != -1) {
                    // 读取过程中摘要持续更新，这里不需要保留数据
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("计算文件哈希失败", e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境不正常
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
