package br.dev.duofinance.goal.repository;

import br.dev.duofinance.goal.model.GoalMovement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalMovementRepository extends JpaRepository<GoalMovement, UUID> {

    List<GoalMovement> findByGoalIdOrderByCreatedAtDesc(UUID goalId);

    Optional<GoalMovement> findByIdAndGoalId(UUID id, UUID goalId);

    boolean existsByGoalIdAndMonth(UUID goalId, LocalDate month);
}
