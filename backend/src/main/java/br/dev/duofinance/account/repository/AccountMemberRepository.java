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

    long countByAccountId(UUID accountId);

    List<AccountMember> findByAccountId(UUID accountId);
}
