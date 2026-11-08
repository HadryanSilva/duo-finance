package br.dev.duofinance.shared.config;

import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc marks a record component as required only when it carries a validation annotation, which response DTOs
 * do not. Without this, every field of every response becomes optional in the generated client types. Response
 * properties are always serialized, so all of them are required; the ones that can be {@code null} are listed here and
 * stay optional (absent or {@code null}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final Map<String, Set<String>> NULLABLE = Map.ofEntries(
            Map.entry(
                    "TransactionResponse",
                    Set.of("description", "category", "destinationBankAccount", "createdBy", "recurringRuleId")),
            Map.entry(
                    "RecurringRuleResponse",
                    Set.of("description", "category", "destinationBankAccount", "endDate", "createdBy")),
            Map.entry("UpcomingOccurrence", Set.of("description", "categoryId")),
            Map.entry("AccessCodeResponse", Set.of("email", "expiresAt", "revokedAt", "note")),
            Map.entry("WaitlistEntryResponse", Set.of("decidedAt")),
            Map.entry("BankAccountResponse", Set.of("holderUserId")),
            Map.entry("SubscriptionResponse", Set.of("currentPeriodEnd")),
            Map.entry("AccountResponse", Set.of("pendingInvitation")));

    private static final Set<String> EXTRA_RESPONSE_SCHEMAS = Set.of(
            "AccountBalance", "CategorySpend", "UpcomingOccurrence", "AuthorRef", "BankAccountRef", "CategoryRef");

    @Bean
    OpenApiCustomizer responsePropertiesRequired() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((name, rawSchema) -> {
                Schema<?> schema = rawSchema;
                if (!isResponse(name) || schema.getProperties() == null) {
                    return;
                }
                Set<String> nullable = NULLABLE.getOrDefault(name, Set.of());
                List<String> required = new ArrayList<>();
                for (String property : schema.getProperties().keySet()) {
                    if (!nullable.contains(property)) {
                        required.add(property);
                    }
                }
                schema.setRequired(required);
            });
        };
    }

    private static boolean isResponse(String name) {
        return name.endsWith("Response") || name.endsWith("Summary") || EXTRA_RESPONSE_SCHEMAS.contains(name);
    }
}
