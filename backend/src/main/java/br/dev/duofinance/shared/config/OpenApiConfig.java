package br.dev.duofinance.shared.config;

import br.dev.duofinance.shared.security.CurrentUser;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc marks a record component as required only when it carries a validation annotation, which response DTOs
 * do not, so every response field would be optional in the generated client types. Response properties are always
 * serialized: every component is required, and the ones annotated {@code @Nullable} are declared nullable
 * ({@code T | null}) instead of optional. See {@link ResponseRecords} for what counts as a response record.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static {
        // Injected by an argument resolver from the JWT; never part of the request contract.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    @Bean
    OpenApiCustomizer responseNullability() {
        Map<String, Class<?>> bySchemaName = new java.util.HashMap<>();
        for (Class<?> type : ResponseRecords.all()) {
            bySchemaName.put(type.getSimpleName(), type);
        }
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((name, rawSchema) -> {
                Schema<?> schema = rawSchema;
                Class<?> type = bySchemaName.get(name.startsWith("PageResponse") ? "PageResponse" : name);
                if (type == null || schema.getProperties() == null) {
                    return;
                }
                Set<String> nullable = new HashSet<>();
                for (RecordComponent component : type.getRecordComponents()) {
                    if (ResponseRecords.isNullable(component)) {
                        nullable.add(component.getName());
                    }
                }
                List<String> required = new ArrayList<>();
                for (String property : new ArrayList<>(schema.getProperties().keySet())) {
                    required.add(property);
                    if (nullable.contains(property)) {
                        Schema<?> original = schema.getProperties().get(property);
                        schema.getProperties().put(property, nullableOf(original));
                    }
                }
                schema.setRequired(required);
            });
        };
    }

    /** {@code anyOf: [original, {type: null}]}, which openapi-typescript renders as {@code T | null}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullableOf(Schema original) {
        Schema wrapper = new Schema();
        wrapper.setAnyOf(List.of(original, new Schema().types(Set.of("null"))));
        return wrapper;
    }
}
