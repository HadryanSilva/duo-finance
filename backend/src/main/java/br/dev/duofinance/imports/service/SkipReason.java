package br.dev.duofinance.imports.service;

public enum SkipReason {
    PENDING,
    REVERTED,
    OTHER_CURRENCY,
    EXCHANGE,
    PRODUCT_IGNORED,
    ZERO_AMOUNT
}
