package br.dev.duofinance.auth.dto;

import br.dev.duofinance.auth.google.GoogleAuthService.SignupRequired;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.user.dto.MeResponse;
import org.jspecify.annotations.Nullable;

/** Either an authenticated session ({@code AUTHENTICATED}) or the data to finish sign-up ({@code SIGNUP_REQUIRED}). */
public record GoogleLoginResponse(
        String status,
        @Nullable String accessToken,
        @Nullable Long expiresIn,
        @Nullable MeResponse user,
        @Nullable String signupTicket,
        @Nullable String email,
        @Nullable String displayName) {

    public static GoogleLoginResponse authenticated(SessionTokens session) {
        return new GoogleLoginResponse(
                "AUTHENTICATED",
                session.access().token(),
                session.access().expiresInSeconds(),
                MeResponse.of(session.user(), session.accountId()),
                null,
                null,
                null);
    }

    public static GoogleLoginResponse signupRequired(SignupRequired required) {
        return new GoogleLoginResponse(
                "SIGNUP_REQUIRED", null, null, null, required.signupTicket(), required.email(), required.displayName());
    }
}
