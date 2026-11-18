package br.dev.duofinance.imports.ai;

import br.dev.duofinance.shared.config.AppProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Claude is enabled only when {@code app.ai.anthropic-api-key} is non-blank; otherwise suggestions are a no-op. */
@Configuration
public class MerchantCategorizerConfiguration {

    @Bean
    MerchantCategorizer merchantCategorizer(AppProperties props, Clock clock) {
        AppProperties.Ai ai = props.ai();
        if (ai == null || ai.anthropicApiKey() == null || ai.anthropicApiKey().isBlank()) {
            return new NoOpMerchantCategorizer();
        }
        return new ClaudeMerchantCategorizer(ai, clock);
    }
}
