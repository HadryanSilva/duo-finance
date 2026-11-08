package br.dev.duofinance.account.service;

import br.dev.duofinance.account.dto.InvitationPreviewResponse;
import br.dev.duofinance.account.dto.InvitationSummary;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.config.AppProperties;
import br.dev.duofinance.shared.email.EmailRequestedEvent;
import br.dev.duofinance.shared.email.EmailTemplate;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    static final Duration TTL = Duration.ofDays(7);

    private final InvitationRepository invitations;
    private final AccountRepository accounts;
    private final AccountMemberRepository members;
    private final UserRepository users;
    private final List<AccountDataProbe> probes;
    private final AccountDeletionService accountDeletion;
    private final AuthSessionService sessions;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    public InvitationService(
            InvitationRepository invitations,
            AccountRepository accounts,
            AccountMemberRepository members,
            UserRepository users,
            List<AccountDataProbe> probes,
            AccountDeletionService accountDeletion,
            AuthSessionService sessions,
            ApplicationEventPublisher events,
            AppProperties props,
            Clock clock) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.members = members;
        this.users = users;
        this.probes = probes;
        this.accountDeletion = accountDeletion;
        this.sessions = sessions;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Locks the inviter's account so concurrent invites serialize. A stale pending invitation is marked EXPIRED first:
     * the partial unique index allows only one PENDING row per account.
     */
    @Transactional
    public InvitationSummary create(CurrentUser current, String email) {
        Instant now = Instant.now(clock);
        User inviter = users.findById(current.userId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
        if (inviter.getEmailVerifiedAt() == null) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        Account account = accounts.findByIdForUpdate(current.accountId())
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
        if (members.countByAccountId(account.getId()) >= Account.MAX_MEMBERS) {
            throw new ApiException(ErrorCode.ACCOUNT_FULL);
        }
        var existing = invitations.findByAccountIdAndStatus(account.getId(), InvitationStatus.PENDING);
        if (existing.isPresent()) {
            if (existing.get().getExpiresAt().isAfter(now)) {
                throw new ApiException(ErrorCode.INVITE_PENDING_EXISTS);
            }
            existing.get().setStatus(InvitationStatus.EXPIRED);
            invitations.flush();
        }
        String invitee = SecureTokens.normalizeEmail(email);
        if (invitee.equals(SecureTokens.normalizeEmail(inviter.getEmail()))) {
            throw new ApiException(ErrorCode.INVITE_SELF);
        }

        String raw = SecureTokens.generate();
        Invitation invitation = invitations.save(new Invitation(
                account.getId(), invitee, SecureTokens.sha256Hex(raw), inviter.getId(), now.plus(TTL), now));
        String base = props.publicBaseUrl().toString().replaceAll("/+$", "");
        events.publishEvent(new EmailRequestedEvent(
                invitee,
                EmailTemplate.COUPLE_INVITATION,
                inviter.getLocale(),
                Map.of(
                        "inviterName", inviter.getDisplayName(),
                        "accountName", account.getName(),
                        "link", base + "/invite/" + raw)));
        return InvitationSummary.of(invitation);
    }

    /** Only a PENDING invitation of the caller's own account can be cancelled; anything else is NOT_FOUND. */
    @Transactional
    public void cancel(CurrentUser current, UUID invitationId) {
        if (invitations.cancelPending(invitationId, current.accountId()) == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    /**
     * Public. Marking an overdue invitation EXPIRED must survive the INVITE_EXPIRED it then throws, hence
     * {@code noRollbackFor}; that is the only write this method performs.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public InvitationPreviewResponse preview(String rawToken) {
        Invitation invitation = requireUsable(rawToken, true);
        String inviterName = invitation.getInvitedBy() == null
                ? null
                : users.findById(invitation.getInvitedBy())
                        .map(User::getDisplayName)
                        .orElse(null);
        String accountName = accounts.findById(invitation.getAccountId())
                .map(Account::getName)
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID));
        boolean existingUser = users.findByEmail(invitation.getEmail()).isPresent();
        return new InvitationPreviewResponse(inviterName, accountName, invitation.getEmail(), existingUser);
    }

    /**
     * Moves the caller from their solo account into the inviting account. Checks run in the documented order. Both
     * accounts are locked in ascending id order so two crossing accepts cannot deadlock.
     */
    @Transactional
    public SessionTokens accept(CurrentUser current, String rawToken, boolean confirmDiscard) {
        Instant now = Instant.now(clock);
        // 1. valid and not expired
        Invitation invitation = requireUsable(rawToken, false);
        // 2. addressed to the caller; otherwise do not reveal that the invitation exists
        User user = users.findById(current.userId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE));
        if (!SecureTokens.normalizeEmail(user.getEmail()).equals(SecureTokens.normalizeEmail(invitation.getEmail()))) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        // 3. caller is alone in a different account
        UUID currentAccountId = current.accountId();
        UUID targetAccountId = invitation.getAccountId();
        if (currentAccountId.equals(targetAccountId)) {
            throw new ApiException(ErrorCode.INVITE_SELF);
        }
        lockInOrder(currentAccountId, targetAccountId);
        if (members.countByAccountId(currentAccountId) != 1) {
            throw new ApiException(ErrorCode.ALREADY_IN_COUPLE);
        }
        // 4. target has room
        if (members.countByAccountId(targetAccountId) >= Account.MAX_MEMBERS) {
            throw new ApiException(ErrorCode.ACCOUNT_FULL);
        }
        // 5. discarding user data needs explicit consent
        if (!confirmDiscard && probes.stream().anyMatch(p -> p.hasUserData(currentAccountId))) {
            throw new ApiException(ErrorCode.ACCOUNT_HAS_DATA);
        }
        // 6. move
        accountDeletion.delete(currentAccountId);
        members.save(new AccountMember(targetAccountId, user.getId(), now));
        if (invitations.markAccepted(invitation.getId(), now) == 0) {
            throw new ApiException(ErrorCode.INVITE_INVALID);
        }
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
            user.setUpdatedAt(now);
        }
        // 7. new session carrying the new account
        return sessions.start(user);
    }

    /** Any consistent total order works; {@link UUID#compareTo} provides one. */
    private void lockInOrder(UUID currentAccountId, UUID targetAccountId) {
        boolean currentFirst = currentAccountId.compareTo(targetAccountId) < 0;
        Optional<Account> first = accounts.findByIdForUpdate(currentFirst ? currentAccountId : targetAccountId);
        Optional<Account> second = accounts.findByIdForUpdate(currentFirst ? targetAccountId : currentAccountId);
        if ((currentFirst ? first : second).isEmpty()) {
            throw new ApiException(ErrorCode.SESSION_STALE);
        }
        if ((currentFirst ? second : first).isEmpty()) {
            throw new ApiException(ErrorCode.INVITE_INVALID);
        }
    }

    /**
     * Unknown, cancelled or accepted → INVITE_INVALID; past {@code expires_at} or already EXPIRED → INVITE_EXPIRED.
     * With {@code markExpired}, an overdue PENDING invitation is set to EXPIRED (the caller must not roll back).
     */
    private Invitation requireUsable(String rawToken, boolean markExpired) {
        Invitation invitation = invitations
                .findByTokenHash(SecureTokens.sha256Hex(rawToken.trim()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID));
        switch (invitation.getStatus()) {
            case EXPIRED -> throw new ApiException(ErrorCode.INVITE_EXPIRED);
            case CANCELED, ACCEPTED -> throw new ApiException(ErrorCode.INVITE_INVALID);
            case PENDING -> {}
        }
        if (!invitation.getExpiresAt().isAfter(Instant.now(clock))) {
            if (markExpired) {
                invitation.setStatus(InvitationStatus.EXPIRED);
            }
            throw new ApiException(ErrorCode.INVITE_EXPIRED);
        }
        return invitation;
    }
}
