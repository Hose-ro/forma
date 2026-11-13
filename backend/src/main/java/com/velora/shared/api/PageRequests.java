package com.velora.shared.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class PageRequests {
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    public static PageRequest of(Integer page, Integer size, Sort sort) {
        if (page != null && (page < 0 || page > 10_000)
                || size != null && (size < 1 || size > MAX_SIZE))
            throw new com.velora.shared.domain.InvalidRequestException("Invalid pagination bounds");
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        return PageRequest.of(p, s, sort);
    }
}
