package dev.relay.common;

import java.util.List;

public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {
    public static <T> Page<T> from(List<T> rows, PageRequest request) {
        return new Page<>(List.copyOf(rows.subList(0, Math.min(rows.size(), request.limit()))),
                request.limit(), request.offset(), rows.size() > request.limit());
    }
}
