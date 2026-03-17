package br.com.hadryan.duo.finance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter in-memory por IP para endpoints de autenticação.
 *
 * Limites configurados:
 *   POST /api/auth/login           → 5 tentativas / 60 s
 *   POST /api/auth/register        → 3 tentativas / 300 s
 *   POST /api/auth/forgot-password → 3 tentativas / 300 s
 *
 * Responde 429 com header Retry-After (segundos até reset do bucket).
 */
@Slf4j
@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    // ── Configuração dos endpoints protegidos ─────────────────────────────────

    private record Limit(int maxRequests, long windowSeconds) {}

    private static final Map<String, Limit> LIMITS = Map.of(
            "/api/auth/login",           new Limit(5,  60),
            "/api/auth/register",        new Limit(3, 300),
            "/api/auth/forgot-password", new Limit(3, 300)
    );

    // ── Estado ────────────────────────────────────────────────────────────────

    /** Chave: "IP::endpoint" → bucket de tentativas */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    // ── Bucket ────────────────────────────────────────────────────────────────

    private static final class Bucket {
        final AtomicInteger count    = new AtomicInteger(0);
        final long          windowMs;
        volatile long       resetAt;

        Bucket(long windowSeconds) {
            this.windowMs = windowSeconds * 1_000;
            this.resetAt  = Instant.now().toEpochMilli() + this.windowMs;
        }

        /** Tenta consumir uma requisição. Retorna false se o limite foi atingido. */
        boolean tryConsume(int max) {
            long now = Instant.now().toEpochMilli();
            if (now >= resetAt) {
                count.set(0);
                resetAt = now + windowMs;
            }
            return count.incrementAndGet() <= max;
        }

        /** Segundos até o reset do bucket. */
        long retryAfterSeconds() {
            return Math.max(1, (resetAt - Instant.now().toEpochMilli()) / 1_000);
        }

        /** Indica se o bucket está expirado e pode ser removido do mapa. */
        boolean isExpired() {
            return Instant.now().toEpochMilli() >= resetAt + windowMs;
        }
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(
            @NotNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String path   = request.getRequestURI();
        String method = request.getMethod();

        // Só processa POST nos endpoints configurados
        Limit limit = "POST".equalsIgnoreCase(method) ? LIMITS.get(path) : null;

        if (limit != null) {
            String ip  = resolveClientIp(request);
            String key = ip + "::" + path;

            Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(limit.windowSeconds()));

            if (!bucket.tryConsume(limit.maxRequests())) {
                long retryAfter = bucket.retryAfterSeconds();
                log.warn("Rate limit atingido | ip={} endpoint={} retry-after={}s", ip, path, retryAfter);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.getWriter().write("""
                        {"error":"Too Many Requests","message":"Muitas tentativas. Aguarde %d segundo(s) antes de tentar novamente.","retryAfter":%d}
                        """.formatted(retryAfter, retryAfter).strip());
                return;
            }
        }

        chain.doFilter(request, response);
    }

    // ── Limpeza periódica — evita vazamento de memória ────────────────────────

    @Scheduled(fixedDelay = 5 * 60 * 1_000) // a cada 5 minutos
    public void evictExpiredBuckets() {
        int before = buckets.size();
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) it.remove();
        }
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("Rate limiter: {} buckets expirados removidos (restam {})", removed, buckets.size());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve o IP real do cliente considerando o Cloudflare Tunnel.
     * Cloudflare injeta CF-Connecting-IP; nginx repassa X-Real-IP e X-Forwarded-For.
     * Prioridade: CF-Connecting-IP > X-Real-IP > X-Forwarded-For > remoteAddr
     */
    private String resolveClientIp(HttpServletRequest request) {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp.trim();

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }
}