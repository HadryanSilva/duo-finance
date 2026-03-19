package br.com.hadryan.duo.finance.notification.listener;

import br.com.hadryan.duo.finance.budget.event.BudgetExceededEvent;
import br.com.hadryan.duo.finance.couple.event.PartnerJoinedEvent;
import br.com.hadryan.duo.finance.goal.GoalAlertEvent;
import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
import br.com.hadryan.duo.finance.notification.NotificationService;
import br.com.hadryan.duo.finance.notification.enums.NotificationType;
import br.com.hadryan.duo.finance.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notificationService;
    private final UserRepository      userRepository;

    // ── Alertas de meta (RF37) ────────────────────────────────────────────────

    @Async
    @EventListener
    public void onGoalAlert(GoalAlertEvent event) {
        try {
            var progress = event.progress();
            boolean exceeded = progress.alertLevel() == GoalDtos.AlertLevel.EXCEEDED;

            NotificationType type  = exceeded ? NotificationType.GOAL_EXCEEDED : NotificationType.GOAL_WARNING;
            String           title = exceeded ? "Meta excedida" : "Meta em atenção";
            String message = exceeded
                    ? "Você ultrapassou o limite de %s em %s. Gasto: R$ %.2f de R$ %.2f."
                    .formatted(progress.categoryLabel(),
                            currentMonthLabel(),
                            progress.spent().doubleValue(),
                            progress.monthlyLimit().doubleValue())
                    : "Você atingiu %.0f%% do limite de %s em %s. Gasto: R$ %.2f de R$ %.2f."
                    .formatted(progress.percentage(),
                            progress.categoryLabel(),
                            currentMonthLabel(),
                            progress.spent().doubleValue(),
                            progress.monthlyLimit().doubleValue());

            // Notifica todos os membros do casal
            userRepository.findByCoupleId(event.coupleId()).forEach(user ->
                    notificationService.create(user, user.getCouple(), type, title, message)
            );
        } catch (Exception e) {
            log.error("Erro ao persistir notificação de meta: {}", e.getMessage());
        }
    }

    // ── Parceiro vinculado (RF45) ─────────────────────────────────────────────

    @Async
    @EventListener
    public void onPartnerJoined(PartnerJoinedEvent event) {
        try {
            String joiningName  = event.joiningUser().getFirstName();
            String existingName = event.existingUser().getFirstName();

            // Notifica o usuário que já estava no casal
            notificationService.create(
                    event.existingUser(),
                    event.couple(),
                    NotificationType.PARTNER_JOINED,
                    "Parceiro vinculado!",
                    "%s aceitou seu convite e agora faz parte do casal.".formatted(joiningName)
            );

            // Notifica o usuário que acabou de entrar
            notificationService.create(
                    event.joiningUser(),
                    event.couple(),
                    NotificationType.PARTNER_JOINED,
                    "Você entrou no casal!",
                    "Você e %s agora compartilham uma conta no DuoFinance.".formatted(existingName)
            );
        } catch (Exception e) {
            log.error("Erro ao persistir notificação de parceiro vinculado: {}", e.getMessage());
        }
    }

    // ── Orçamento excedido ────────────────────────────────────────────────────

    @Async
    @EventListener
    public void onBudgetExceeded(BudgetExceededEvent event) {
        try {
            double overage = event.spent()
                    .subtract(event.allocated())
                    .divide(event.allocated(), 4, RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100))
                    .doubleValue();

            String title   = "Orçamento excedido";
            String message = "O orçamento de %s foi excedido em %.0f%%. Gasto: R$ %.2f de R$ %.2f alocados."
                    .formatted(event.category().getLabel(), overage,
                            event.spent().doubleValue(), event.allocated().doubleValue());

            userRepository.findByCoupleId(event.coupleId()).forEach(user ->
                    notificationService.create(user, user.getCouple(),
                            NotificationType.BUDGET_EXCEEDED, title, message)
            );
        } catch (Exception e) {
            log.error("Erro ao persistir notificação de orçamento excedido: {}", e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String currentMonthLabel() {
        var now = java.time.LocalDate.now();
        return now.getMonth()
                .getDisplayName(java.time.format.TextStyle.FULL, new java.util.Locale("pt", "BR"))
                + "/" + now.getYear();
    }
}