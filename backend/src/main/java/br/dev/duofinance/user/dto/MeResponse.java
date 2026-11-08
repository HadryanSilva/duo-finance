package br.dev.duofinance.user.dto;

import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserRole;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String displayName,
        String locale,
        boolean emailVerified,
        UserRole role,
        UUID accountId) {

    public static MeResponse of(User user, UUID accountId) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getLocale(),
                user.getEmailVerifiedAt() != null,
                user.getRole(),
                accountId);
    }
}
