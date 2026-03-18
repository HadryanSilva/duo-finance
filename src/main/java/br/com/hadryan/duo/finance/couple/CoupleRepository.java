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

    /** Casais com exatamente 2 membros vinculados. */
    @Query("SELECT COUNT(c) FROM couples c WHERE SIZE(c.members) = 2")
    long countComplete();

}
