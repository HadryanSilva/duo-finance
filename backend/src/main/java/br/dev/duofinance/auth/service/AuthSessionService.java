package br.dev.duofinance.auth.service;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.auth.model.RefreshToken;
import br.dev.duofinance.auth.repository.RefreshTokenRepository;
import br.dev.duofinance.auth.service.TokenService.IssuedAccessToken;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rotating refresh-token sessions with reuse detection and a short grace window for concurrent renewals. */
@Service
public class AuthSessionService {

    static final Duration REFRESH_TTL = Duration.ofDays(30);
    static final Duration RACE_GRACE = Duration.ofSeconds(10);

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final AccountMemberRepository members;
    private final UserIdentityRepository identities;
    private final TokenService tokenService;
    private final Clock clock;

    public AuthSessionService(
            RefreshTokenRepository refreshTokens,
            UserRepository users,
            AccountMemberRepository members,
            UserIdentityRepository identities,
            TokenService tokenService,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.members = members;
        this.identities = identities;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    /** Starts a new token family for the user. */
    @Transactional
    public SessionTokens start(User user) {
        Instant now = Instant.now(clock);
        String raw = SecureTokens.generate();
        refreshTokens.save(new RefreshToken(
                user.getId(), UUID.randomUUID(), SecureTokens.sha256Hex(raw), now.plus(REFRESH_TTL), now));
        return sessionFor(user, raw);
    }

    /**
     * Rotates the token. Family revocation on reuse must survive the thrown exception, hence
     * {@code noRollbackFor}; the race path performs no writes at all.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SessionTokens refresh(String rawRefreshToken) {
        Instant now = Instant.now(clock);
        RefreshToken current = refreshTokens
                .findByTokenHashForUpdate(SecureTokens.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (current.getRevokedAt() != null) {
            boolean rotatedJustNow = current.getReplacedById() != null
                    && Duration.between(current.getRevokedAt(), now).compareTo(RACE_GRACE) < 0;
            if (rotatedJustNow) {
                throw new ApiException(ErrorCode.REFRESH_RACE);
            }
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        User user = users.findById(current.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));
        String raw = SecureTokens.generate();
        RefreshToken next = refreshTokens.save(new RefreshToken(
                user.getId(), current.getFamilyId(), SecureTokens.sha256Hex(raw), now.plus(REFRESH_TTL), now));
        current.setRevokedAt(now);
        current.setReplacedById(next.getId());
        return sessionFor(user, raw);
    }

    @Transactional
    public void revokeFamilyOf(String rawRefreshToken) {
        refreshTokens
                .findByTokenHashForUpdate(SecureTokens.sha256Hex(rawRefreshToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), Instant.now(clock)));
    }

    /**
     * Revokes every refresh token and moves the user's session epoch to now, so access tokens issued before it are
     * rejected too. The epoch is truncated to whole seconds because {@code iat} is: a session started right after this
     * call stays valid (a token minted earlier within the same second also survives; that window is accepted). Both
     * are bulk updates that flush pending entity changes first; the second one also clears the persistence context.
     */
    @Transactional
    public void revokeAll(UUID userId) {
        Instant now = Instant.now(clock);
        users.invalidateSessionsBefore(userId, now.truncatedTo(ChronoUnit.SECONDS));
        refreshTokens.revokeAllForUser(userId, now);
    }

    private SessionTokens sessionFor(User user, String raw) {
        UUID accountId = members.findAccountIdByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("User " + user.getId() + " has no account membership"));
        IssuedAccessToken access = tokenService.issueAccessToken(user, accountId);
        return new SessionTokens(user, accountId, access, raw, identities.findByUserId(user.getId()));
    }

    public record SessionTokens(
            User user,
            UUID accountId,
            IssuedAccessToken access,
            String rawRefreshToken,
            List<UserIdentity> identities) {}
}
