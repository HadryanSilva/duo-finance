package br.dev.duofinance.account.dto;

/** {@code inviterName} is null if the inviter's user no longer exists. */
public record InvitationPreviewResponse(String inviterName, String accountName, String email, boolean existingUser) {}
