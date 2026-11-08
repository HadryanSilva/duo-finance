package br.dev.duofinance.account.repository;

import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findFirstByAccountIdAndStatusAndExpiresAtAfter(
            UUID accountId, InvitationStatus status, Instant now);

    /** At most one row: a partial unique index allows a single PENDING invitation per account. */
    Optional<Invitation> findByAccountIdAndStatus(UUID accountId, InvitationStatus status);

    /** Accepts the invitation only if it is still pending; returns the number of rows changed (0 or 1). */
    @Modifying(flushAutomatically = true)
    @Query("update Invitation i set i.status = br.dev.duofinance.account.model.InvitationStatus.ACCEPTED,"
            + " i.acceptedAt = :now where i.id = :id"
            + " and i.status = br.dev.duofinance.account.model.InvitationStatus.PENDING")
    int markAccepted(@Param("id") UUID id, @Param("now") Instant now);

    /** Cancels the account's pending invitation, if any; returns the number of rows changed. */
    @Modifying(flushAutomatically = true)
    @Query("update Invitation i set i.status = br.dev.duofinance.account.model.InvitationStatus.CANCELED"
            + " where i.accountId = :accountId"
            + " and i.status = br.dev.duofinance.account.model.InvitationStatus.PENDING")
    int cancelPendingByAccount(@Param("accountId") UUID accountId);

    /** Cancels every pending invitation created by the user; returns the number of rows changed. */
    @Modifying(flushAutomatically = true)
    @Query("update Invitation i set i.status = br.dev.duofinance.account.model.InvitationStatus.CANCELED"
            + " where i.invitedBy = :userId"
            + " and i.status = br.dev.duofinance.account.model.InvitationStatus.PENDING")
    int cancelPendingByInviter(@Param("userId") UUID userId);
}
