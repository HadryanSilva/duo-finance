package br.dev.duofinance.access.repository;

import br.dev.duofinance.access.model.AccessCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessCodeRepository extends JpaRepository<AccessCode, UUID> {

    Optional<AccessCode> findByCodeHash(String codeHash);

    /**
     * Atomically redeems one use. The UPDATE takes the row lock and, under READ COMMITTED, re-evaluates the WHERE
     * clause after waiting, so concurrent redemptions of a single-use code cannot both succeed, regardless of what
     * the persistence context cached earlier in the transaction. Returns the number of rows updated (0 or 1).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update AccessCode c set c.usedCount = c.usedCount + 1
            where c.codeHash = :codeHash and c.revokedAt is null and c.usedCount < c.maxUses
              and (c.expiresAt is null or c.expiresAt > :now)
              and (c.email is null or c.email = :email)
            """)
    int redeem(@Param("codeHash") String codeHash, @Param("email") String email, @Param("now") Instant now);
}
