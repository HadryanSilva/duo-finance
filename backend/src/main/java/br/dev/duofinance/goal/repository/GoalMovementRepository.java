package br.dev.duofinance.goal.repository;

import br.dev.duofinance.goal.model.GoalMovement;
import br.dev.duofinance.goal.model.GoalMovementType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalMovementRepository extends JpaRepository<GoalMovement, UUID> {

    List<GoalMovement> findByGoalIdOrderByCreatedAtDescIdDesc(UUID goalId);

    Optional<GoalMovement> findByIdAndGoalId(UUID id, UUID goalId);

    boolean existsByGoalIdAndMonthAndType(UUID goalId, LocalDate month, GoalMovementType type);

    List<GoalMovement> findByGoalIdAndMonthAndType(UUID goalId, LocalDate month, GoalMovementType type);

    long deleteByGoalIdAndMonthAndType(UUID goalId, LocalDate month, GoalMovementType type);

    List<GoalMovement> findByAccountIdAndImportBatchId(UUID accountId, UUID importBatchId);
}
