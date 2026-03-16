package br.com.hadryan.duo.finance.goal;

import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goalService;

    public GoalController(GoalService goalService) {
        this.goalService = goalService;
    }

    /**
     * GET /api/goals
     * Lista todas as metas do casal (ativas e pausadas).
     */
    @GetMapping
    public ResponseEntity<List<GoalDtos.GoalResponse>> listAll(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(goalService.listAll(currentUser));
    }

    /**
     * POST /api/goals
     * Cria uma nova meta mensal para a categoria.
     */
    @PostMapping
    public ResponseEntity<GoalDtos.GoalResponse> create(
            @Valid @RequestBody GoalDtos.CreateGoalRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(goalService.create(request, currentUser));
    }

    /**
     * PUT /api/goals/{id}
     * Atualiza o limite mensal de uma meta.
     */
    @PutMapping("/{id}")
    public ResponseEntity<GoalDtos.GoalResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody GoalDtos.UpdateGoalRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(goalService.update(id, request, currentUser));
    }

    /**
     * PATCH /api/goals/{id}/toggle
     * Ativa ou pausa uma meta sem excluí-la.
     */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<GoalDtos.GoalResponse> toggle(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(goalService.toggleActive(id, currentUser));
    }

    /**
     * DELETE /api/goals/{id}
     * Remove uma meta permanentemente.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        goalService.delete(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/goals/progress
     * RF36 — Progresso de todas as metas ativas no mês atual.
     */
    @GetMapping("/progress")
    public ResponseEntity<List<GoalDtos.GoalProgressResponse>> progress(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(goalService.getProgress(currentUser));
    }
}