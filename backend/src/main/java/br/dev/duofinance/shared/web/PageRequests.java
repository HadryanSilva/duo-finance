package br.dev.duofinance.shared.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class PageRequests {

    public static final int MAX_SIZE = 100;

    private PageRequests() {}

    /** Clamps {@code page} to {@code >= 0} and {@code size} to {@code 1..100}. */
    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE), sort);
    }
}
