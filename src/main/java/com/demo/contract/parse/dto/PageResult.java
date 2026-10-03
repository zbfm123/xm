package com.demo.contract.parse.dto;

import java.util.List;

/**
 * 分页结果。
 *
 * @param page  当前页码，**从 1 开始**（对外契约；内部 offset 由 Service 转成 0 基）
 * @param size  每页条数
 * @param total 满足条件的总条数
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /** 总页数，供前端渲染分页器。 */
    public int totalPages() {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
