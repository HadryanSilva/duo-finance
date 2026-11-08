package br.dev.duofinance.account.repository;

import br.dev.duofinance.account.model.Invitation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findFirstByAccountIdAndStatusAndExpiresAtAfter(
            java.util.UUID accountId, br.dev.duofinance.account.model.InvitationStatus status, java.time.Instant now);
}
