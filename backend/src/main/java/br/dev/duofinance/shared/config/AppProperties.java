package br.dev.duofinance.shared.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Validated so a missing {@code app.public-base-url} fails startup instead of every e-mail-sending request. */
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(@NotNull URI publicBaseUrl, Registration registration, Auth auth, Email email) {

    public enum RegistrationMode {
        INVITE_ONLY,
        OPEN
    }

    public enum EmailProvider {
        LOG,
        RESEND
    }

    public record Registration(@DefaultValue("INVITE_ONLY") RegistrationMode mode) {}

    /** PEM-encoded RSA keys; both optional. */
    public record Auth(
            String jwtPrivateKey,
            String jwtPublicKey,
            @DefaultValue("true") boolean cookieSecure) {}

    public record Email(String from, @DefaultValue("RESEND") EmailProvider provider, String resendApiKey) {}
}
