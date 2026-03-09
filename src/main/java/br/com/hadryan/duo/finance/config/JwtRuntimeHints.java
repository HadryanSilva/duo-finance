package br.com.hadryan.duo.finance.config;

import org.jspecify.annotations.NonNull;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Component;

@Component
@ImportRuntimeHints(JwtRuntimeHints.class)
public class JwtRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(@NonNull RuntimeHints hints, ClassLoader classLoader) {
        // jjwt classes loaded via Class.forName at runtime
        String[] classes = {
                "io.jsonwebtoken.impl.security.KeysBridge",
                "io.jsonwebtoken.impl.security.DefaultJwtBuilder",
                "io.jsonwebtoken.impl.security.DefaultJwtParser",
                "io.jsonwebtoken.impl.DefaultJwtBuilder",
                "io.jsonwebtoken.impl.DefaultJwtParserBuilder",
                "io.jsonwebtoken.impl.DefaultClaims",
                "io.jsonwebtoken.impl.lang.Services",
                "io.jsonwebtoken.impl.security.StandardSecureDigestAlgorithms",
                "io.jsonwebtoken.impl.security.StandardKeyAlgorithms",
                "io.jsonwebtoken.impl.security.StandardEncryptionAlgorithms",
                "io.jsonwebtoken.impl.compression.DefaultCompressionCodecResolver"
        };

        for (String clazz : classes) {
            try {
                hints.reflection().registerType(
                        Class.forName(clazz, false, classLoader),
                        MemberCategory.values()
                );
            } catch (ClassNotFoundException ignored) {
                // classe pode não existir nessa versão do jjwt
            }
        }
    }
}
