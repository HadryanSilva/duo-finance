package br.dev.duofinance.subscription.repository;

import br.dev.duofinance.subscription.model.Subscription;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByAccountId(UUID accountId);
}
