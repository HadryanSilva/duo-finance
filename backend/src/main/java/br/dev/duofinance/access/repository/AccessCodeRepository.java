package br.dev.duofinance.access.repository;

import br.dev.duofinance.access.model.AccessCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessCodeRepository extends JpaRepository<AccessCode, UUID> {

    Optional<AccessCode> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AccessCode c where c.codeHash = :codeHash")
    Optional<AccessCode> findByCodeHashForUpdate(@Param("codeHash") String codeHash);
}
