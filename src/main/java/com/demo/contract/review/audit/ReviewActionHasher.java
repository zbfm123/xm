package com.demo.contract.review.audit;

import com.demo.contract.review.domain.ReviewActionRow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 复核记录的哈希链计算。
 *
 * <p><b>哈希链的作用是让"事后修改某条记录"变成可检测的。</b>
 * 每条记录的哈希把前一条的哈希算进去，于是改动任意一条，
 * 其后所有记录的哈希都会对不上，校验时会指出断点位置。
 *
 * <p>⚠️ <b>它防的是篡改，不是抵赖。</b>
 * 知道算法与字段顺序的人可以重算整条链，从而伪造一个自洽的历史。
 * 真正的不可抵赖需要外部时间戳或数字签名（由第三方持有私钥）。
 * 这是已知限制，必须在面试时主动说明，不能宣称"不可篡改"。
 *
 * <p>链首的 {@code previousHash} 是一串 0 而不是 null：
 * null 会让"链首"与"字段缺失"混在一起，而 64 个 0 只有"链首"一种含义。
 */
public final class ReviewActionHasher {

    /** 链首的前驱哈希。用全 0 而不是 null，避免与"字段缺失"混淆。 */
    public static final String GENESIS_HASH = "0".repeat(64);

    private ReviewActionHasher() {
    }

    /**
     * 计算一条记录的哈希。
     *
     * <p>参与计算的字段与顺序是<b>约定的一部分</b>：
     * 改变字段集合或顺序会使已有链全部失效，因此这里显式列出并加注释，
     * 不做"反射遍历所有字段"那种看起来聪明但顺序不稳定的做法。
     *
     * <p>用 {@code \u0001} 作分隔符：它对正常文本几乎不可能出现，
     * 从而避免"字段内容挪位后拼出的字符串相同"这种边界伪造。
     */
    public static String compute(ReviewActionRow row) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(row.getPreviousHash()).append('\u0001')
          .append(row.getIdempotencyKey()).append('\u0001')
          .append(row.getContractId()).append('\u0001')
          // finding_id 可能为 null：用固定标记占位，不能直接 append(null)
          .append(row.getFindingId() == null ? "-" : row.getFindingId()).append('\u0001')
          .append(row.getAction()).append('\u0001')
          .append(row.getReason() == null ? "-" : row.getReason()).append('\u0001')
          .append(row.getOperatorId()).append('\u0001')
          .append(row.getOperatorName()).append('\u0001')
          .append(row.getPreviousStatus() == null ? "-" : row.getPreviousStatus()).append('\u0001')
          .append(row.getNewStatus());
        return sha256Hex(sb.toString());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制支持的算法，走到这里说明运行环境异常
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }

    /**
     * 校验结果。
     *
     * @param intact     整条链是否完好
     * @param checked    校验了多少条
     * @param brokenAt   第一个校验失败的记录 id；完好时为 null
     * @param reason     失败原因（便于人工定位）
     */
    public record ChainVerification(
            boolean intact,
            int checked,
            Long brokenAt,
            String reason
    ) {
        public static ChainVerification ok(int checked) {
            return new ChainVerification(true, checked, null, null);
        }

        public static ChainVerification broken(int checked, Long brokenAt, String reason) {
            return new ChainVerification(false, checked, brokenAt, reason);
        }
    }
}
