package br.dev.duofinance.account.dto;

/** {@code confirmDiscard} acknowledges that the caller's current solo account and its data will be deleted. */
public record AcceptInvitationRequest(boolean confirmDiscard) {}
