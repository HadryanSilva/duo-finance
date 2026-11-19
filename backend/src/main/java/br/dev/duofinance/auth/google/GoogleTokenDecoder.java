package br.dev.duofinance.auth.google;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Verifies the signature and timestamps of a Google ID token. Deliberately NOT a {@code JwtDecoder}: the Resource
 * Server must keep using the application's own decoder.
 */
public interface GoogleTokenDecoder {

    /** @throws JwtException when the signature, format or timestamps are invalid */
    Jwt decode(String idToken);
}
