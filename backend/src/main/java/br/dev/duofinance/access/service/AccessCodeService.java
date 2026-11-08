package br.dev.duofinance.access.service;

import br.dev.duofinance.access.model.AccessCode;
import br.dev.duofinance.access.repository.AccessCodeRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessCodeService {

    /** Crockford base32: no I, L, O, U. */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    static final int CODE_LENGTH = 12;
    static final int GROUP_SIZE = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccessCodeRepository codes;
    private final Clock clock;

    public AccessCodeService(AccessCodeRepository codes, Clock clock) {
        this.codes = codes;
        this.clock = clock;
    }

    @Transactional
    public GeneratedAccessCode generate(String email, int maxUses, Instant expiresAt, String note, UUID createdBy) {
        String plain = randomCode();
        String normalizedEmail = email == null || email.isBlank() ? null : SecureTokens.normalizeEmail(email);
        AccessCode saved = codes.save(
                new AccessCode(hash(plain), normalizedEmail, maxUses, expiresAt, note, createdBy, Instant.now(clock)));
        return new GeneratedAccessCode(saved.getId(), plain);
    }

    /** Non-locking pre-check used by the registration gate; {@link #consume} redeems atomically. */
    @Transactional(readOnly = true)
    public void check(String plainCode, String normalizedEmail) {
        AccessCode code = codes.findByCodeHash(hash(plainCode)).orElseThrow(AccessCodeService::invalid);
        requireRedeemable(code, normalizedEmail);
    }

    @Transactional
    public void consume(String plainCode, String normalizedEmail) {
        if (codes.redeem(hash(plainCode), normalizedEmail, Instant.now(clock)) == 0) {
            throw invalid();
        }
    }

    @Transactional
    public void revoke(UUID id) {
        AccessCode code = codes.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (code.getRevokedAt() == null) {
            code.setRevokedAt(Instant.now(clock));
        }
    }

    static String normalizeCode(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private void requireRedeemable(AccessCode code, String normalizedEmail) {
        if (!code.isRedeemableBy(normalizedEmail, Instant.now(clock))) {
            throw invalid();
        }
    }

    private static String hash(String plainCode) {
        return SecureTokens.sha256Hex(normalizeCode(plainCode));
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.ACCESS_CODE_INVALID);
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH + 2);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0 && i % GROUP_SIZE == 0) {
                sb.append('-');
            }
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public record GeneratedAccessCode(UUID id, String plainCode) {}
}
