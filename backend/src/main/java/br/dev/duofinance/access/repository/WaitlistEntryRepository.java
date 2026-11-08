package br.dev.duofinance.access.repository;

import br.dev.duofinance.access.model.WaitlistEntry;
import br.dev.duofinance.access.model.WaitlistStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    /** Emails are always stored normalized, so plain equality is correct. */
    boolean existsByEmail(String email);

    Page<WaitlistEntry> findByStatus(WaitlistStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WaitlistEntry e where e.id = :id")
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") UUID id);
}
