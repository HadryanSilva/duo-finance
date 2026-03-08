package br.com.hadryan.duo.finance.report.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;

import java.math.BigDecimal;

public record CategorySumProjection(
        TransactionCategory category,
        BigDecimal total
) {}
