package br.dev.duofinance.account.repository;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What a request needs to trust a token: the user's account and their session epoch (null when never revoked). */
public record MemberSession(UUID accountId, @Nullable Instant sessionsValidAfter) {}
