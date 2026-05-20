package com.edsp.common.api;

import java.util.List;

public record PageResult<T>(List<T> items, long total) {
    public static <T> PageResult<T> of(List<T> items, long total) {
        return new PageResult<>(items, total);
    }
}
