package br.dev.duofinance.account.repository;

import br.dev.duofinance.account.model.AccountMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountMemberRepository extends JpaRepository<AccountMember, AccountMember.Key> {

    @Query("select m.accountId from AccountMember m where m.userId = :userId")
    Optional<UUID> findAccountIdByUserId(UUID userId);

    /** The account and session epoch of a user in one round trip, for per-request token validation. */
    @Query("""
            select new br.dev.duofinance.account.repository.MemberSession(m.accountId, u.sessionsValidAfter)
            from AccountMember m join User u on u.id = m.userId
            where m.userId = :userId""")
    Optional<MemberSession> findSessionByUserId(UUID userId);

    long countByAccountId(UUID accountId);

    boolean existsByAccountIdAndUserId(UUID accountId, UUID userId);

    List<AccountMember> findByAccountId(UUID accountId);
}
