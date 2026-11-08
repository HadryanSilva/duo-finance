package br.dev.duofinance.account.dto;

import java.time.Instant;
import java.util.UUID;

public record MemberResponse(UUID userId, String displayName, String email, Instant joinedAt) {}
