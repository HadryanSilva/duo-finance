package br.dev.duofinance.auth.service;

import br.dev.duofinance.access.service.AccessCodeService;
import br.dev.duofinance.access.service.RegistrationGate;
import br.dev.duofinance.access.service.RegistrationGate.CodeGrant;
import br.dev.duofinance.access.service.RegistrationGate.InvitationGrant;
import br.dev.duofinance.access.service.RegistrationGate.RegistrationGrant;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.account.service.AccountProvisioningService;
import br.dev.duofinance.auth.dto.GoogleCompleteRequest;
import br.dev.duofinance.auth.dto.RegisterRequest;
import br.dev.duofinance.auth.google.GoogleIdentity;
import br.dev.duofinance.auth.google.SignupTicketService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BiConsumer;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    /** Unique constraint on {@code users.email} (V1__identity.sql). */
    private static final String USERS_EMAIL_CONSTRAINT = "users_email_key";

    /** Unique constraint on {@code user_identities (provider, provider_subject)} (V1__identity.sql). */
    private static final String IDENTITY_SUBJECT_CONSTRAINT = "user_identities_provider_subject_key";

    private final RegistrationGate gate;
    private final AccessCodeService accessCodes;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PasswordEncoder passwordEncoder;
    private final AccountRepository accounts;
    private final AccountMemberRepository members;
    private final InvitationRepository invitations;
    private final AccountProvisioningService provisioning;
    private final EmailVerificationService emailVerification;
    private final AuthSessionService sessions;
    private final SignupTicketService tickets;
    private final Clock clock;

    public RegistrationService(
            RegistrationGate gate,
            AccessCodeService accessCodes,
            UserRepository users,
            UserIdentityRepository identities,
            PasswordEncoder passwordEncoder,
            AccountRepository accounts,
            AccountMemberRepository members,
            InvitationRepository invitations,
            AccountProvisioningService provisioning,
            EmailVerificationService emailVerification,
            AuthSessionService sessions,
            SignupTicketService tickets,
            Clock clock) {
        this.gate = gate;
        this.accessCodes = accessCodes;
        this.users = users;
        this.identities = identities;
        this.passwordEncoder = passwordEncoder;
        this.accounts = accounts;
        this.members = members;
        this.invitations = invitations;
        this.provisioning = provisioning;
        this.emailVerification = emailVerification;
        this.sessions = sessions;
        this.tickets = tickets;
        this.clock = clock;
    }

    /**
     * Single transaction with default rollback: a failure after {@code consume} undoes the consumption. Step order
     * matters: credential first, then e-mail uniqueness, then consumption.
     */
    @Transactional
    public SessionTokens register(RegisterRequest req) {
        String email = SecureTokens.normalizeEmail(req.email());
        RegistrationGrant grant = gate.authorize(req.accessCode(), req.invitationToken(), email);
        return signUp(grant, email, req.displayName(), req.locale(), req.termsVersion(), false, (user, now) -> {
            identities.save(new UserIdentity(
                    user.getId(), IdentityProvider.LOCAL, email, passwordEncoder.encode(req.password()), now));
        });
    }

    /**
     * Same gate as {@link #register}, but the e-mail comes from a verified Google identity in the signup ticket: the
     * user is created verified, linked to that Google subject, and gets no verification e-mail.
     */
    @Transactional
    public SessionTokens registerWithGoogle(GoogleCompleteRequest req) {
        GoogleIdentity google = tickets.read(req.signupTicket());
        String email = SecureTokens.normalizeEmail(google.email());
        RegistrationGrant grant = gate.authorize(req.accessCode(), req.invitationToken(), email);
        return signUp(grant, email, req.displayName(), req.locale(), req.termsVersion(), true, (user, now) -> {
            saveGoogleIdentity(new UserIdentity(user.getId(), IdentityProvider.GOOGLE, google.subject(), null, now));
        });
    }

    private SessionTokens signUp(
            RegistrationGrant grant,
            String email,
            String displayName,
            String locale,
            String termsVersion,
            boolean emailVerified,
            BiConsumer<User, Instant> saveIdentity) {
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        if (grant instanceof CodeGrant code) {
            accessCodes.consume(code.plainCode(), email);
        }

        Instant now = Instant.now(clock);
        User newUser = new User(email, displayName.trim(), locale, termsVersion, now);
        if (emailVerified) {
            newUser.setEmailVerifiedAt(now);
        }
        User user = saveUser(newUser);
        saveIdentity.accept(user, now);

        if (grant instanceof InvitationGrant invitationGrant) {
            joinInvitedAccount(user, invitationGrant.invitation(), now);
        } else {
            provisioning.createSoloAccount(user);
            if (!emailVerified) {
                emailVerification.sendVerification(user);
            }
        }
        return sessions.start(user);
    }

    /** A Google subject already tied to another user means a concurrent sign-up with the same ticket won. */
    private void saveGoogleIdentity(UserIdentity identity) {
        try {
            identities.saveAndFlush(identity);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, IDENTITY_SUBJECT_CONSTRAINT)) {
                throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            throw e;
        }
    }

    /** Only the e-mail unique constraint means a concurrent sign-up won; any other violation is a real error. */
    private User saveUser(User user) {
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, USERS_EMAIL_CONSTRAINT)) {
                throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            throw e;
        }
    }

    private static boolean violates(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && constraint.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The invitation link came from the inbox, so the e-mail counts as verified. The gate read the invitation without a
     * lock; the conditional update re-checks that it is still PENDING, so a concurrent cancel or replace wins. The
     * managed {@code invitation} entity is left untouched so dirty checking cannot overwrite that status.
     */
    private void joinInvitedAccount(User user, Invitation invitation, Instant now) {
        accounts.findByIdForUpdate(invitation.getAccountId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID));
        if (invitations.markAccepted(invitation.getId(), now) == 0) {
            throw new ApiException(ErrorCode.INVITE_INVALID);
        }
        if (members.countByAccountId(invitation.getAccountId()) >= Account.MAX_MEMBERS) {
            throw new ApiException(ErrorCode.ACCOUNT_FULL);
        }
        members.save(new AccountMember(invitation.getAccountId(), user.getId(), now));
        user.setEmailVerifiedAt(now);
        // The couple already has a name and there is no partner left to invite.
        user.setOnboardedAt(now);
    }
}
