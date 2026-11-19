package br.dev.duofinance.user.repository;

import br.dev.duofinance.user.model.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String normalizedEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Sets the session epoch. Flushes pending entity changes first; it does not clear the persistence context, and a
     * managed {@link User} keeps its old (unwritten) value, which {@code @DynamicUpdate} never writes back.
     */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.sessionsValidAfter = :at where u.id = :id")
    int invalidateSessionsBefore(@Param("id") UUID id, @Param("at") Instant at);
}
