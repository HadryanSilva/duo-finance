package br.com.hadryan.duo.finance.auth.jwt;

import br.com.hadryan.duo.finance.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    public JwtService(JwtProperties props) {
        log.debug(props.secret());
        this.signingKey = Keys.hmacShaKeyFor(
                props.secret().getBytes(StandardCharsets.UTF_8)
        );
        this.accessTokenExpirationMs = props.accessTokenExpirationMs();
    }

    // ── Geração ───────────────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email",     user.getEmail())
                .claim("firstName", user.getFirstName())
                .claim("lastName",  user.getLastName())
                .claim("coupleId",  user.getCouple() != null
                        ? user.getCouple().getId().toString()
                        : null)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // ── Validação e extração ──────────────────────────────────────────────────

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token JWT inválido: {}", e.getMessage());
            return false;
        }
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public UUID extractCoupleId(String token) {
        String coupleId = parseClaims(token).get("coupleId", String.class);
        return coupleId != null ? UUID.fromString(coupleId) : null;
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
