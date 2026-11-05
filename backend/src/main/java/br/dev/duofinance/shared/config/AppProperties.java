package br.dev.duofinance.shared.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(URI publicBaseUrl, Registration registration, Auth auth, Email email) {

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
