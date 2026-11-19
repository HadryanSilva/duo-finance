package br.dev.duofinance.user.dto;

import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import br.dev.duofinance.user.model.UserRole;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String displayName,
        String locale,
        boolean emailVerified,
        boolean onboarded,
        UserRole role,
        UUID accountId,
        boolean hasPassword,
        List<String> providers) {

    public static MeResponse of(User user, UUID accountId, List<UserIdentity> identities) {
        boolean hasPassword = identities.stream()
                .anyMatch(i -> i.getProvider() == IdentityProvider.LOCAL && i.getPasswordHash() != null);
        List<String> providers = identities.stream()
                .map(i -> i.getProvider().name())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getLocale(),
                user.getEmailVerifiedAt() != null,
                user.getOnboardedAt() != null,
                user.getRole(),
                accountId,
                hasPassword,
                providers);
    }
}
