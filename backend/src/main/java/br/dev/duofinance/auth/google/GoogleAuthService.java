package br.dev.duofinance.auth.google;

import br.dev.duofinance.auth.repository.UserTokenRepository;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Google sign-in: signs in a linked account, links by verified e-mail, or asks the client to finish sign-up. */
@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier verifier;
    private final SignupTicketService tickets;
    private final UserIdentityRepository identities;
    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final AuthSessionService sessions;
    private final Clock clock;

    public GoogleAuthService(
            GoogleIdTokenVerifier verifier,
            SignupTicketService tickets,
            UserIdentityRepository identities,
            UserRepository users,
            UserTokenRepository userTokens,
            AuthSessionService sessions,
            Clock clock) {
        this.verifier = verifier;
        this.tickets = tickets;
        this.identities = identities;
        this.users = users;
        this.userTokens = userTokens;
        this.sessions = sessions;
        this.clock = clock;
    }

    public sealed interface GoogleAuthResult permits Authenticated, SignupRequired {}

    public record Authenticated(SessionTokens session) implements GoogleAuthResult {}

    public record SignupRequired(String signupTicket, String email, String displayName) implements GoogleAuthResult {}

    @Transactional
    public GoogleAuthResult authenticate(String credential) {
        GoogleIdentity google = verifier.verify(credential);

        Optional<User> user = identities
                .findByProviderAndProviderSubject(IdentityProvider.GOOGLE, google.subject())
                .flatMap(i -> users.findById(i.getUserId()));
        if (user.isEmpty()) {
            user = users.findByEmail(google.email()).flatMap(u -> users.findByIdForUpdate(u.getId()));
            user.ifPresent(u -> link(u, google));
        }
        if (user.isEmpty()) {
            return new SignupRequired(tickets.issue(google), google.email(), displayNameOf(google));
        }
        return new Authenticated(sessions.start(user.get()));
    }

    private void link(User user, GoogleIdentity google) {
        Optional<UserIdentity> existing = identities.findByUserIdAndProvider(user.getId(), IdentityProvider.GOOGLE);
        if (existing.isPresent()) {
            // The same Google account already linked (double click, or a concurrent request that won): nothing to do.
            if (existing.get().getProviderSubject().equals(google.subject())) {
                return;
            }
            throw new ApiException(ErrorCode.GOOGLE_ACCOUNT_CONFLICT);
        }
        Instant now = Instant.now(clock);
        identities.save(new UserIdentity(user.getId(), IdentityProvider.GOOGLE, google.subject(), null, now));
        if (user.getEmailVerifiedAt() == null) {
            // Pre-hijack protection: whoever registered this e-mail never proved they own it, so the password
            // they set, any pending e-mail tokens and any session they hold must not survive the verified link.
            // The user is updated before revokeAll, whose bulk update clears the persistence context.
            user.setEmailVerifiedAt(now);
            user.setUpdatedAt(now);
            identities.deleteByUserIdAndProvider(user.getId(), IdentityProvider.LOCAL);
            userTokens.deleteUnusedByUserId(user.getId());
            sessions.revokeAll(user.getId());
        }
    }

    private static String displayNameOf(GoogleIdentity google) {
        if (google.name() != null && !google.name().isBlank()) {
            return google.name();
        }
        return google.email().substring(0, google.email().indexOf('@'));
    }
}
