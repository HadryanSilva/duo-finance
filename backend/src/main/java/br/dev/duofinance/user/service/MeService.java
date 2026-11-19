package br.dev.duofinance.user.service;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.account.service.AccountDeletionService;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.dto.DeleteMeRequest;
import br.dev.duofinance.user.dto.MeResponse;
import br.dev.duofinance.user.dto.UpdateMeRequest;
import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessions;
    private final AccountRepository accounts;
    private final AccountMemberRepository members;
    private final InvitationRepository invitations;
    private final AccountDeletionService accountDeletion;
    private final Clock clock;

    public MeService(
            UserRepository users,
            UserIdentityRepository identities,
            PasswordEncoder passwordEncoder,
            AuthSessionService sessions,
            AccountRepository accounts,
            AccountMemberRepository members,
            InvitationRepository invitations,
            AccountDeletionService accountDeletion,
            Clock clock) {
        this.accounts = accounts;
        this.members = members;
        this.invitations = invitations;
        this.accountDeletion = accountDeletion;
        this.users = users;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeResponse get(CurrentUser current) {
        return view(load(current), current);
    }

    @Transactional
    public MeResponse update(CurrentUser current, UpdateMeRequest request) {
        User user = load(current);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.locale() != null) {
            user.setLocale(request.locale());
        }
        user.setUpdatedAt(Instant.now(clock));
        return view(user, current);
    }

    /** Marks onboarding as done (finished or skipped). Idempotent: the first completion time is kept. */
    @Transactional
    public MeResponse completeOnboarding(CurrentUser current) {
        User user = load(current);
        if (user.getOnboardedAt() == null) {
            Instant now = Instant.now(clock);
            user.setOnboardedAt(now);
            user.setUpdatedAt(now);
        }
        return view(user, current);
    }

    /** Replaces the password, revokes every session and starts a fresh one, atomically. */
    @Transactional
    public SessionTokens changePassword(CurrentUser current, String currentPassword, String newPassword) {
        User user = load(current);
        UserIdentity identity = identities
                .findByUserIdAndProvider(user.getId(), IdentityProvider.LOCAL)
                .filter(i -> i.getPasswordHash() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(currentPassword, identity.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        identity.setPasswordHash(passwordEncoder.encode(newPassword));
        sessions.revokeAll(user.getId());
        return sessions.start(user);
    }

    /**
     * GDPR self-deletion. Locks the account before counting members so two partners deleting at once cannot each see
     * a partner and leave an orphaned account. A sole member takes the account with them; otherwise the account stays
     * with the partner. The database cascades remove identities and tokens and null out {@code invited_by}.
     */
    @Transactional
    public void delete(CurrentUser current, DeleteMeRequest request) {
        User user = load(current);
        confirmDeletion(user, request);
        sessions.revokeAll(user.getId());
        accounts.findByIdForUpdate(current.accountId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
        invitations.cancelPendingByInviter(user.getId());
        if (members.countByAccountId(current.accountId()) == 1) {
            accountDeletion.delete(current.accountId());
        }
        users.delete(user);
        users.flush();
    }

    /** With a password the user must prove it; without one, retyping their own e-mail is the confirmation. */
    private void confirmDeletion(User user, DeleteMeRequest request) {
        UserIdentity local = identities
                .findByUserIdAndProvider(user.getId(), IdentityProvider.LOCAL)
                .filter(i -> i.getPasswordHash() != null)
                .orElse(null);
        boolean confirmed = local != null
                ? request.password() != null && passwordEncoder.matches(request.password(), local.getPasswordHash())
                : request.confirmEmail() != null
                        && SecureTokens.normalizeEmail(request.confirmEmail()).equals(user.getEmail());
        if (!confirmed) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    private MeResponse view(User user, CurrentUser current) {
        return MeResponse.of(user, current.accountId(), identities.findByUserId(user.getId()));
    }

    private User load(CurrentUser current) {
        return users.findById(current.userId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
    }
}
