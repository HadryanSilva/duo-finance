package br.com.hadryan.duo.finance.goal;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
import br.com.hadryan.duo.finance.report.ReportRepository;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository           goalRepository;
    private final ReportRepository         reportRepository;
    private final ApplicationEventPublisher eventPublisher;

    // ── RF35: Criar meta ──────────────────────────────────────────────────────

    @Transactional
    public GoalDtos.GoalResponse create(GoalDtos.CreateGoalRequest request, User currentUser) {
        Couple couple = requireCouple(currentUser);

        if (goalRepository.existsByCoupleIdAndCategory(couple.getId(), request.category())) {
            throw new BusinessException(
                    "Já existe uma meta para a categoria " + request.category().getLabel());
        }

        Goal goal = new Goal();
        goal.setCouple(couple);
        goal.setCategory(request.category());
        goal.setMonthlyLimit(request.monthlyLimit());

        return toResponse(goalRepository.save(goal));
    }

    // ── RF35: Atualizar meta ──────────────────────────────────────────────────

    @Transactional
    public GoalDtos.GoalResponse update(UUID id, GoalDtos.UpdateGoalRequest request, User currentUser) {
        Couple couple = requireCouple(currentUser);
        Goal goal = requireGoal(id, couple.getId());

        goal.setMonthlyLimit(request.monthlyLimit());

        return toResponse(goalRepository.save(goal));
    }

    // ── RF35: Excluir meta ────────────────────────────────────────────────────

    @Transactional
    public void delete(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        Goal goal = requireGoal(id, couple.getId());
        goalRepository.delete(goal);
    }

    // ── RF35: Ativar / pausar meta ────────────────────────────────────────────

    @Transactional
    public GoalDtos.GoalResponse toggleActive(UUID id, User currentUser) {
        Couple couple = requireCouple(currentUser);
        Goal goal = requireGoal(id, couple.getId());
        goal.setActive(!goal.isActive());
        return toResponse(goalRepository.save(goal));
    }

    // ── RF35: Listar metas ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GoalDtos.GoalResponse> listAll(User currentUser) {
        Couple couple = requireCouple(currentUser);
        return goalRepository.findAllByCoupleId(couple.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── RF36: Progresso de todas as metas do mês atual ────────────────────────

    @Transactional(readOnly = true)
    public List<GoalDtos.GoalProgressResponse> getProgress(User currentUser) {
        Couple couple = requireCouple(currentUser);
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end   = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        return goalRepository.findActiveByCoupleId(couple.getId())
                .stream()
                .map(goal -> buildProgress(goal, couple.getId(), start, end))
                .toList();
    }

    // ── RF37: Verificar alertas após nova transação ───────────────────────────

    /**
     * Chamado pelo TransactionService após criar/editar uma transação EXPENSE.
     * Publica GoalAlertEvent se a meta atingiu 80% ou 100%.
     */
    @Transactional(readOnly = true)
    public void checkAndPublishAlerts(UUID coupleId, TransactionCategory category) {
        goalRepository.findByCoupleIdAndCategory(coupleId, category)
                .filter(Goal::isActive)
                .ifPresent(goal -> {
                    LocalDate start = LocalDate.now().withDayOfMonth(1);
                    LocalDate end   = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

                    GoalDtos.GoalProgressResponse progress = buildProgress(goal, coupleId, start, end);

                    if (progress.alertLevel() != GoalDtos.AlertLevel.NONE) {
                        eventPublisher.publishEvent(new GoalAlertEvent(coupleId, progress));
                    }
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GoalDtos.GoalProgressResponse buildProgress(Goal goal, UUID coupleId,
                                                        LocalDate start, LocalDate end) {
        // Reutiliza ReportRepository.sumByType — já existente no projeto
        BigDecimal spent = reportRepository.sumByType(
                coupleId,
                TransactionType.EXPENSE,
                start,
                end
        );

        // Filtra apenas a categoria da meta via sumByCategory
        // sumByCategory retorna lista; buscamos a categoria específica
        BigDecimal spentInCategory = reportRepository.sumByCategory(coupleId, TransactionType.EXPENSE, start, end)
                .stream()
                .filter(p -> p.category() == goal.getCategory())
                .map(p -> p.total())
                .findFirst()
                .orElse(BigDecimal.ZERO);

        BigDecimal limit     = goal.getMonthlyLimit();
        BigDecimal remaining = limit.subtract(spentInCategory).max(BigDecimal.ZERO);
        double percentage    = limit.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : spentInCategory.divide(limit, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        GoalDtos.AlertLevel alertLevel = GoalDtos.AlertLevel.NONE;
        if (percentage >= 100.0)      alertLevel = GoalDtos.AlertLevel.EXCEEDED;
        else if (percentage >= 80.0)  alertLevel = GoalDtos.AlertLevel.WARNING;

        return new GoalDtos.GoalProgressResponse(
                goal.getId(),
                goal.getCategory(),
                goal.getCategory().getLabel(),
                limit,
                spentInCategory,
                remaining,
                Math.min(percentage, 100.0),
                alertLevel,
                goal.isActive()
        );
    }

    private GoalDtos.GoalResponse toResponse(Goal goal) {
        return new GoalDtos.GoalResponse(
                goal.getId(),
                goal.getCategory(),
                goal.getCategory().getLabel(),
                goal.getMonthlyLimit(),
                goal.isActive(),
                goal.getCreatedAt(),
                goal.getUpdatedAt()
        );
    }

    private Couple requireCouple(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple();
    }

    private Goal requireGoal(UUID id, UUID coupleId) {
        return goalRepository.findByIdAndCoupleId(id, coupleId)
                .orElseThrow(() -> new ResourceNotFoundException("Meta não encontrada: " + id));
    }
}
