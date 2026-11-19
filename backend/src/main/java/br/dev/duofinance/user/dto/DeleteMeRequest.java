package br.dev.duofinance.user.dto;

import org.jspecify.annotations.Nullable;

/** A user with a password confirms with it; a password-less (Google) user confirms with their e-mail. */
public record DeleteMeRequest(
        @Nullable String password, @Nullable String confirmEmail) {}
