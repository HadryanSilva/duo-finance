package br.com.hadryan.duo.finance.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Carrega o RefreshToken junto com o User em uma única query.
     * Necessário para evitar LazyInitializationException ao acessar
     * user.email fora da sessão JPA (ex: AuthController.refresh).
     */
    @Query("SELECT rt FROM RefreshToken rt JOIN FETCH rt.user u LEFT JOIN FETCH u.couple WHERE rt.token = :token")
    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId")
    void revokeAllByUserId(UUID userId);
}
