package br.dev.duofinance.access.service;

import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.auth.dto.RegisterRequest;
import br.dev.duofinance.shared.config.AppProperties;
import br.dev.duofinance.shared.config.AppProperties.RegistrationMode;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decides whether a registration attempt is allowed, and on which credential. Does not consume anything. */
@Service
public class RegistrationGate {

    private final AppProperties props;
    private final AccessCodeService accessCodes;
    private final InvitationRepository invitations;
    private final Clock clock;

    public RegistrationGate(
            AppProperties props, AccessCodeService accessCodes, InvitationRepository invitations, Clock clock) {
        this.props = props;
        this.accessCodes = accessCodes;
        this.invitations = invitations;
        this.clock = clock;
    }

    /** An invitation token takes priority over an access code. */
    @Transactional(readOnly = true)
    public RegistrationGrant authorize(RegisterRequest req, String normalizedEmail) {
        if (hasText(req.invitationToken())) {
            return new InvitationGrant(requireValidInvitation(req.invitationToken(), normalizedEmail));
        }
        if (hasText(req.accessCode())) {
            accessCodes.check(req.accessCode(), normalizedEmail);
            return new CodeGrant(req.accessCode());
        }
        if (props.registration().mode() == RegistrationMode.OPEN) {
            return new OpenGrant();
        }
        throw new ApiException(ErrorCode.ACCESS_CODE_INVALID);
    }

    private Invitation requireValidInvitation(String token, String normalizedEmail) {
        Invitation invitation = invitations
                .findByTokenHash(SecureTokens.sha256Hex(token.trim()))
                .filter(i -> i.getStatus() == InvitationStatus.PENDING)
                .filter(i -> i.getEmail().equals(normalizedEmail))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID));
        if (!invitation.getExpiresAt().isAfter(Instant.now(clock))) {
            throw new ApiException(ErrorCode.INVITE_EXPIRED);
        }
        return invitation;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    public sealed interface RegistrationGrant permits OpenGrant, CodeGrant, InvitationGrant {}

    public record OpenGrant() implements RegistrationGrant {}

    public record CodeGrant(String plainCode) implements RegistrationGrant {}

    public record InvitationGrant(Invitation invitation) implements RegistrationGrant {}
}
