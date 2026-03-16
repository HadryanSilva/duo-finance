package br.com.hadryan.duo.finance.goal;

import br.com.hadryan.duo.finance.goal.dto.GoalDtos;

import java.util.UUID;

/**
 * Evento publicado pelo GoalService quando uma meta atinge 80% (WARNING)
 * ou 100% (EXCEEDED) do limite mensal — RF37 / RF47.
 */
public record GoalAlertEvent(
        UUID coupleId,
        GoalDtos.GoalProgressResponse progress
) {}