package br.com.hadryan.duo.finance.report.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionType;

import java.math.BigDecimal;

public record MonthlySumProjection(
        int year,
        int month,
        TransactionType type,
        BigDecimal total
) {}
