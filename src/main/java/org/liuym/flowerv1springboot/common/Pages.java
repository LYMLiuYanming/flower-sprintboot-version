package org.liuym.flowerv1springboot.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 分页参数收口：限制单页上限与深度，防止 limit=99999 把整表拉进内存
 */
public final class Pages {

    public static final int MAX_SIZE = 100;

    private static final int MAX_PAGE = 10000;

    private Pages() {
    }

    public static Pageable of(int page, int limit) {
        return PageRequest.of(index(page), size(limit));
    }

    public static Pageable of(int page, int limit, Sort.Direction direction, String... properties) {
        return PageRequest.of(index(page), size(limit), Sort.by(direction, properties));
    }

    public static Pageable of(int page, int limit, Sort sort) {
        return PageRequest.of(index(page), size(limit), sort);
    }

    private static int index(int page) {
        return Math.min(Math.max(page, 1), MAX_PAGE) - 1;
    }

    private static int size(int limit) {
        return Math.min(Math.max(limit, 1), MAX_SIZE);
    }
}
