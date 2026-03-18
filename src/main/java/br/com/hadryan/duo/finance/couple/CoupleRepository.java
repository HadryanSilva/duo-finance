package br.com.hadryan.duo.finance.couple;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CoupleRepository extends JpaRepository<Couple, UUID> {

    Optional<Couple> findByInviteToken(String inviteToken);

    // ── Métricas de negócio ───────────────────────────────────────────────────

    /**
     * Casais com pelo menos 1 membro ativo (couple_id não nulo em algum usuário).
     * Exclui casais "fantasma" que ficaram sem membros após desvincular.
     */
    @Query("SELECT COUNT(DISTINCT u.couple.id) FROM users u WHERE u.couple IS NOT NULL")
    long countActive();

    /**
     * Casais com exatamente 2 membros ativos.
     */
    @Query("""
            SELECT COUNT(c) FROM couples c
            WHERE (SELECT COUNT(u) FROM users u WHERE u.couple.id = c.id) = 2
            """)
    long countComplete();

}
