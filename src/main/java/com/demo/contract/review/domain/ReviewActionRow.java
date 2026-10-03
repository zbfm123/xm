package com.demo.contract.review.domain;

import java.time.LocalDateTime;

/**
 * 人工复核动作记录。
 *
 * <h3>为什么这个类是可变行对象（有 setter），而不是不可变值对象</h3>
 *
 * <p>我最初把它写成了不可变类：字段 final、只有全参构造函数、没有 setter。
 * 想法是"审计记录不该能被改动"。
 *
 * <p><b>结果直接踩了 MyBatis 的构造器自动映射。</b>
 * 返回类型只有一个构造函数时，MyBatis 会按<b>列顺序</b>把列塞给构造参数
 * （{@code applyColumnOrderBasedConstructorAutomapping}）。
 * 症状是 {@code Error attempting to get column 'reason'} 且来自
 * {@code LongTypeHandler}——它正拿 {@code reason}（字符串）去填某个 {@code Long}。
 * <b>看起来像中文编码问题，实际是列顺序与构造参数顺序不一致。</b>
 *
 * <p>我依次试过三条路，都拦不住它：
 * <ol>
 *   <li>写完整的 {@code @Results} —— 仍触发自动映射</li>
 *   <li>用 {@code @ResultType} 指定手写 {@linkplain com.demo.contract.review.mapper.ReviewActionRowTypeHandler TypeHandler} —— 未被采用</li>
 *   <li>{@code @AutomapConstructor} —— 仍然按列顺序</li>
 * </ol>
 *
 * <p>于是改成可变行对象。这个取舍是经过权衡的：
 *
 * <blockquote>
 * <b>不变式的保障本来就不该靠 Java 的 final 字段。</b>
 * </blockquote>
 *
 * <p>"复核记录不可修改"实际由三件事保证，而它们都比 final 更硬：
 * <ol>
 *   <li>{@code ReviewActionMapper} <b>没有</b> update/delete 方法（有测试反射强制）</li>
 *   <li>数据库 <b>BEFORE UPDATE / BEFORE DELETE 触发器</b>直接拒绝</li>
 *   <li>哈希链让"绕过前两条的改动"可被检测出来</li>
 * </ol>
 *
 * <p>相比之下，final 字段只能防住"通过这个类的方法改动"，
 * 而任何人都能直接写 SQL 绕过去。**把成本花在真正有效的那一层上。**
 */
public class ReviewActionRow {

    private Long id;
    private Long tenantId;
    private Long contractId;
    private Long findingId;
    private String idempotencyKey;
    private String action;
    private String reason;
    private Long operatorId;
    private String operatorName;
    private String previousStatus;
    private String newStatus;
    private String recordHash;
    private String previousHash;
    private LocalDateTime createdAt;

    /** MyBatis 需要一个无参构造函数来按属性名映射。 */
    public ReviewActionRow() {
    }

    /**
     * 便于测试与阅读的全参构造函数。
     *
     * <p>⚠️ 它<b>不是</b>给 MyBatis 用的——存在两个构造函数后，
     * MyBatis 就不会再走"单构造函数"的按列顺序映射了。
     */
    public ReviewActionRow(Long tenantId, Long contractId, Long findingId,
                           String idempotencyKey, String action, String reason,
                           Long operatorId, String operatorName,
                           String previousStatus, String newStatus,
                           String recordHash, String previousHash) {
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.findingId = findingId;
        this.idempotencyKey = idempotencyKey;
        this.action = action;
        this.reason = reason;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.recordHash = recordHash;
        this.previousHash = previousHash;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public Long getFindingId() { return findingId; }
    public void setFindingId(Long findingId) { this.findingId = findingId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
    public String getNewStatus() { return newStatus; }
    public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
    public String getRecordHash() { return recordHash; }
    public void setRecordHash(String recordHash) { this.recordHash = recordHash; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
