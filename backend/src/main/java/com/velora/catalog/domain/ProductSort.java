package com.velora.catalog.domain;

import com.velora.shared.domain.InvalidRequestException;
import java.util.Locale;
import org.springframework.data.domain.Sort;

public enum ProductSort {
    FEATURED(Sort.by(Sort.Order.desc("featured"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"))),
    PRICE_ASC(Sort.by(Sort.Order.asc("price"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"))),
    PRICE_DESC(Sort.by(Sort.Order.desc("price"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"))),
    NEWEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }

    public static ProductSort parse(String value) {
        if (value == null || value.isBlank()) return FEATURED;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("sort must be one of: featured, price_asc, price_desc, newest");
        }
    }
}
