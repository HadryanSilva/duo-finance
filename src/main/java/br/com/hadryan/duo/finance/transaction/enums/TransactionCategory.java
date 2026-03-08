package br.com.hadryan.duo.finance.transaction.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum TransactionCategory {

    // ── Despesas ──────────────────────────────────────────────────────────────
    FOOD            ("Alimentação",            TransactionType.EXPENSE),
    HOUSING         ("Moradia",               TransactionType.EXPENSE),
    TRANSPORT       ("Transporte",            TransactionType.EXPENSE),
    HEALTH          ("Saúde",                 TransactionType.EXPENSE),
    EDUCATION       ("Educação",              TransactionType.EXPENSE),
    LEISURE         ("Lazer",                 TransactionType.EXPENSE),
    CLOTHING        ("Roupas & Beleza",        TransactionType.EXPENSE),
    PETS            ("Pets",                  TransactionType.EXPENSE),
    SUBSCRIPTIONS   ("Serviços & Assinaturas", TransactionType.EXPENSE),
    OTHER_EXPENSE   ("Outros",                TransactionType.EXPENSE),

    // ── Receitas ──────────────────────────────────────────────────────────────
    SALARY          ("Salário",               TransactionType.INCOME),
    FREELANCE       ("Freelance",             TransactionType.INCOME),
    INVESTMENTS     ("Investimentos",         TransactionType.INCOME),
    RENTAL          ("Aluguel recebido",      TransactionType.INCOME),
    GIFT            ("Presente / Doação",     TransactionType.INCOME),
    OTHER_INCOME    ("Outros",                TransactionType.INCOME);

    private final String label;
    private final TransactionType type;

    TransactionCategory(String label, TransactionType type) {
        this.label = label;
        this.type  = type;
    }

    /** Retorna apenas as categorias de um tipo específico. Útil para popular selects no frontend. */
    public static List<TransactionCategory> byType(TransactionType type) {
        return Arrays.stream(values())
                .filter(c -> c.type == type)
                .toList();
    }
}
