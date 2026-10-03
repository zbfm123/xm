package com.demo.contract.parse.dto;

/**
 * 上传结果。
 *
 * @param idempotent true 表示这次请求命中了已有合同（同一文件已上传过），
 *                   并没有新建记录。把这个信息明确告诉调用方，
 *                   比让前端自己对比 id 猜要可靠。
 */
public record UploadResult(ContractSummary contract, boolean idempotent) {
}
