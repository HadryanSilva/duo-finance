package br.dev.duofinance.shared.security;

import br.dev.duofinance.user.model.UserRole;
import java.util.UUID;

/** The authenticated caller, with {@code accountId} confirmed against the database. Declare it as a controller parameter. */
public record CurrentUser(UUID userId, UUID accountId, UserRole role) {}
