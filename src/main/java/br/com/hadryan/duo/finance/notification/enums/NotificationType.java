package br.com.hadryan.duo.finance.notification.enums;

public enum NotificationType {
    GOAL_WARNING,     // meta atingiu 80%
    GOAL_EXCEEDED,    // meta atingiu 100%
    BUDGET_EXCEEDED,  // orçamento de categoria excedido
    PARTNER_JOINED    // parceiro se vinculou ao casal
}