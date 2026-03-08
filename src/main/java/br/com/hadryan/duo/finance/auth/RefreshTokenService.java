package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.auth.jwt.JwtProperties;
import br.com.hadryan.duo.finance.shared.exception.InvalidTokenException;
import br.com.hadryan.duo.finance.user.User;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final long expirationMs;

    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties props) {
        this.repository   = repository;
        this.expirationMs = props.refreshTokenExpirationMs();
    }

    // ── Criação ───────────────────────────────────────────────────────────────

    @Transactional
    public RefreshToken create(User user) {
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(expirationMs / 1000);

        RefreshToken token = new RefreshToken(
                user,
                UUID.randomUUID().toString(),   // valor opaco e único
                expiresAt
        );

        return repository.save(token);
    }

    // ── Validação e rotação ───────────────────────────────────────────────────

    /**
     * Valida o token, revoga o atual e retorna um novo (rotação).
     * Lança {@link InvalidTokenException} se inválido ou expirado.
     */
    @Transactional
    public RefreshToken rotate(String tokenValue) {
        RefreshToken existing = repository.findByToken(tokenValue)
                .orElseThrow(() -> new InvalidTokenException("Refresh token não encontrado"));

        if (!existing.isValid()) {
            // Possível reutilização de token roubado — revoga todos do usuário
            repository.revokeAllByUserId(existing.getUser().getId());
            throw new InvalidTokenException("Refresh token inválido ou expirado");
        }

        existing.revoke();
        repository.save(existing);

        return create(existing.getUser());
    }

    // ── Revogação (logout) ────────────────────────────────────────────────────

    @Transactional
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllByUserId(userId);
    }

}
