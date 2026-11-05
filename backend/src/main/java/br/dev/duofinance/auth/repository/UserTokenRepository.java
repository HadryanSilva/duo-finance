package br.dev.duofinance.auth.repository;

import br.dev.duofinance.auth.model.UserToken;
import br.dev.duofinance.auth.model.UserTokenType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {

    Optional<UserToken> findByTokenHashAndType(String tokenHash, UserTokenType type);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now where t.userId = :userId and t.type = :type and t.usedAt is null")
    int invalidateUnused(@Param("userId") UUID userId, @Param("type") UserTokenType type, @Param("now") Instant now);
}
