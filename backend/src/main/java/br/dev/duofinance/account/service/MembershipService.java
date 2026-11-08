package br.dev.duofinance.account.service;

import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.bankaccount.repository.BankAccountRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {

    private final AccountRepository accounts;
    private final AccountMemberRepository members;
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final BankAccountRepository bankAccounts;
    private final AccountProvisioningService provisioning;
    private final AuthSessionService sessions;

    public MembershipService(
            AccountRepository accounts,
            AccountMemberRepository members,
            InvitationRepository invitations,
            UserRepository users,
            BankAccountRepository bankAccounts,
            AccountProvisioningService provisioning,
            AuthSessionService sessions) {
        this.accounts = accounts;
        this.members = members;
        this.invitations = invitations;
        this.users = users;
        this.bankAccounts = bankAccounts;
        this.provisioning = provisioning;
        this.sessions = sessions;
    }

    /**
     * Moves the caller out of a couple into a fresh solo account; data and subscription stay with the partner. The
     * account row is locked before counting so two partners leaving at once cannot both see two members. The leaver
     * stops being holder of the account's bank accounts, since a holder must be a current member.
     */
    @Transactional
    public SessionTokens leave(CurrentUser current) {
        User user = users.findById(current.userId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
        accounts.findByIdForUpdate(current.accountId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
        if (members.countByAccountId(current.accountId()) < 2) {
            throw new ApiException(ErrorCode.NOT_IN_COUPLE);
        }
        invitations.cancelPendingByAccount(current.accountId());
        bankAccounts.clearHolder(current.accountId(), user.getId());
        members.deleteById(new AccountMember.Key(current.accountId(), user.getId()));
        members.flush();
        provisioning.createSoloAccount(user);
        return sessions.start(user);
    }
}
