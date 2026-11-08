package br.dev.duofinance.account.dto;

import org.jspecify.annotations.Nullable;

/** {@code inviterName} is null if the inviter's user no longer exists. */
public record InvitationPreviewResponse(
        @Nullable String inviterName, String accountName, String email, boolean existingUser) {}
