package br.dev.duofinance.account.service;

import br.dev.duofinance.account.dto.AccountResponse;
import br.dev.duofinance.account.dto.InvitationSummary;
import br.dev.duofinance.account.dto.MemberResponse;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AccountMemberRepository members;
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final Clock clock;

    public AccountService(
            AccountRepository accounts,
            AccountMemberRepository members,
            InvitationRepository invitations,
            UserRepository users,
            Clock clock) {
        this.accounts = accounts;
        this.members = members;
        this.invitations = invitations;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccountResponse get(CurrentUser current) {
        return toResponse(load(current.accountId()));
    }

    @Transactional
    public AccountResponse rename(CurrentUser current, String name) {
        Account account = load(current.accountId());
        account.setName(name.trim());
        account.setUpdatedAt(Instant.now(clock));
        return toResponse(account);
    }

    private Account load(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
    }

    private AccountResponse toResponse(Account account) {
        List<AccountMember> rows = members.findByAccountId(account.getId()).stream()
                .sorted(Comparator.comparing(AccountMember::getJoinedAt))
                .toList();
        Map<UUID, User> usersById = users
                .findAllById(rows.stream().map(AccountMember::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<MemberResponse> memberDtos = rows.stream()
                .map(m -> {
                    User u = usersById.get(m.getUserId());
                    return new MemberResponse(u.getId(), u.getDisplayName(), u.getEmail(), m.getJoinedAt());
                })
                .toList();
        InvitationSummary pending = invitations
                .findFirstByAccountIdAndStatusAndExpiresAtAfter(
                        account.getId(), InvitationStatus.PENDING, Instant.now(clock))
                .map(InvitationSummary::of)
                .orElse(null);
        return new AccountResponse(account.getId(), account.getName(), account.getCurrency(), memberDtos, pending);
    }
}
