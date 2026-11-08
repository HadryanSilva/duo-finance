package br.dev.duofinance.auth.controller;

import br.dev.duofinance.shared.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookies {

    static final String NAME = "df_refresh";
    static final String PATH = "/api/v1/auth";
    static final Duration MAX_AGE = Duration.ofDays(30);

    private final boolean secure;

    public RefreshCookies(AppProperties props) {
        this.secure = props.auth().cookieSecure();
    }

    public void set(HttpServletResponse response, String raw) {
        write(response, raw, MAX_AGE);
    }

    public void clear(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> !v.isBlank())
                .findFirst();
    }

    private void write(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
