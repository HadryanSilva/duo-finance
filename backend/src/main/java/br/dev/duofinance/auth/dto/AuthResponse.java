package br.dev.duofinance.auth.dto;

import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.user.dto.MeResponse;

public record AuthResponse(String accessToken, long expiresIn, MeResponse user) {

    public static AuthResponse of(SessionTokens session) {
        return new AuthResponse(
                session.access().token(),
                session.access().expiresInSeconds(),
                MeResponse.of(session.user(), session.accountId()));
    }
}
