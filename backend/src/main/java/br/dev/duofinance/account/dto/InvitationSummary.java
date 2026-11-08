package br.dev.duofinance.account.dto;

import br.dev.duofinance.account.model.Invitation;
import java.time.Instant;
import java.util.UUID;

public record InvitationSummary(UUID id, String email, Instant expiresAt) {

    public static InvitationSummary of(Invitation invitation) {
        return new InvitationSummary(invitation.getId(), invitation.getEmail(), invitation.getExpiresAt());
    }
}
