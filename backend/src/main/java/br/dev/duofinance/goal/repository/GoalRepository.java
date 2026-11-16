package br.dev.duofinance.goal.repository;

import br.dev.duofinance.goal.model.Goal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    List<Goal> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<Goal> findByIdAndAccountId(UUID id, UUID accountId);
}
