package br.dev.duofinance.budget.dto;

public enum BudgetStatus {
    NORMAL,
    WARNING,
    EXCEEDED;

    public static BudgetStatus of(int percent) {
        if (percent >= 100) {
            return EXCEEDED;
        }
        return percent >= 80 ? WARNING : NORMAL;
    }
}
