package br.dev.duofinance.account.dto;

import java.util.List;
import java.util.UUID;

/** {@code pendingInvitation} is null when there is no unexpired pending invitation. */
public record AccountResponse(
        UUID id, String name, String currency, List<MemberResponse> members, InvitationSummary pendingInvitation) {}
