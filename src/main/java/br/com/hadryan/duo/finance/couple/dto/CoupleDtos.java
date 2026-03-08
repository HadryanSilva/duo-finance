package br.com.hadryan.duo.finance.couple.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class CoupleDtos {

    // ── Requests ──────────────────────────────────────────────────────────────

    public record CreateCoupleRequest(
            @NotBlank @Size(max = 100) String name
    ) {}

    public record UpdateCoupleRequest(
            @NotBlank @Size(max = 100) String name
    ) {}

    public record InvitePartnerRequest(
            @NotBlank String partnerEmail
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    public record CoupleResponse(
            String id,
            String name,
            List<PartnerResponse> members,
            boolean waitingForPartner,
            LocalDateTime createdAt
    ) {}

    public record PartnerResponse(
            String id,
            String firstName,
            String lastName,
            String email,
            String avatarUrl
    ) {}

    public record InviteResponse(
            String message,
            String partnerEmail,
            LocalDateTime expiresAt
    ) {}

    public record JoinCoupleResponse(
            String message,
            CoupleResponse couple
    ) {}

}
