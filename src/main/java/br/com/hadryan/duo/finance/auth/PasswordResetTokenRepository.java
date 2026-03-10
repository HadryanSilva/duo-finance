package br.com.hadryan.duo.finance.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByToken(String token);

    // Invalida todos os tokens anteriores do usuário antes de emitir um novo
    @Modifying
    @Query("UPDATE password_reset_tokens t SET t.used = true WHERE t.user.id = :userId")
    void invalidateAllByUserId(UUID userId);
}
