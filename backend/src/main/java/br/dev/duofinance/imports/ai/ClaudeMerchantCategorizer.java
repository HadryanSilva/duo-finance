package br.dev.duofinance.imports.ai;

import br.dev.duofinance.shared.config.AppProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks Claude to pick a category for merchant descriptions. Only indexes, descriptions and category names leave the
 * process. Every failure degrades to "no answer" (and is not cached) so the import preview never depends on the API.
 */
public class ClaudeMerchantCategorizer implements MerchantCategorizer {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMerchantCategorizer.class);

    static final int MAX_DESCRIPTION_CHARS = 100;
    private static final long MAX_TOKENS = 4000;
    private static final String SYSTEM_PROMPT = "You categorize card-payment merchants for a couple's budgeting app. "
            + "For each item choose the most likely category index from the list, or null if no category is a "
            + "reasonable fit. Never invent categories.";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record CacheKey(UUID accountId, String descriptionKey, UUID categoriesHash) {}

    private final AnthropicClient client;
    private final String model;
    private final Cache<CacheKey, Optional<UUID>> cache;

    public ClaudeMerchantCategorizer(AppProperties.Ai props, Clock clock) {
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(props.anthropicApiKey())
                .timeout(props.timeout())
                .maxRetries(0);
        if (props.baseUrl() != null && !props.baseUrl().isBlank()) {
            builder.baseUrl(props.baseUrl());
        }
        this.client = builder.build();
        this.model = props.model();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(10_000)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    @Override
    public Map<String, UUID> categorize(UUID accountId, List<Merchant> merchants, List<CategoryOption> categories) {
        Map<String, UUID> result = new HashMap<>();
        try {
            if (merchants.isEmpty() || categories.isEmpty()) {
                return result;
            }
            UUID categoriesHash = hashOf(categories);
            List<Merchant> missing = new ArrayList<>();
            for (Merchant merchant : merchants) {
                Optional<UUID> cached =
                        cache.getIfPresent(new CacheKey(accountId, merchant.descriptionKey(), categoriesHash));
                if (cached == null) {
                    missing.add(merchant);
                } else {
                    cached.ifPresent(id -> result.put(merchant.descriptionKey(), id));
                }
            }
            if (missing.isEmpty()) {
                return result;
            }
            Map<Integer, Integer> answers = ask(missing, categories);
            if (answers == null) {
                return result;
            }
            for (int i = 0; i < missing.size(); i++) {
                Integer categoryIndex = answers.get(i);
                UUID chosen = categoryIndex != null && categoryIndex >= 0 && categoryIndex < categories.size()
                        ? categories.get(categoryIndex).id()
                        : null;
                String key = missing.get(i).descriptionKey();
                cache.put(new CacheKey(accountId, key, categoriesHash), Optional.ofNullable(chosen));
                if (chosen != null) {
                    result.put(key, chosen);
                }
            }
        } catch (RuntimeException e) {
            logFailure(e);
        }
        return result;
    }

    /** Item index to category index; {@code null} when the call failed or the answer was unusable. */
    private Map<Integer, Integer> ask(List<Merchant> items, List<CategoryOption> categories) {
        try {
            Message message = client.messages()
                    .create(MessageCreateParams.builder()
                            .model(model)
                            .maxTokens(MAX_TOKENS)
                            .system(SYSTEM_PROMPT)
                            .outputConfig(OutputConfig.builder()
                                    .effort(OutputConfig.Effort.LOW)
                                    .format(JsonOutputFormat.builder()
                                            .schema(schema())
                                            .build())
                                    .build())
                            .addUserMessage(userMessage(items, categories))
                            .build());
            if (message.stopReason().filter(StopReason.END_TURN::equals).isEmpty()) {
                log.warn(
                        "AI categorization ended without end_turn: stopReason={}",
                        message.stopReason().map(StopReason::toString).orElse("none"));
                return null;
            }
            return parse(message);
        } catch (Exception e) {
            logFailure(e);
            return null;
        }
    }

    private static Map<Integer, Integer> parse(Message message) throws IOException {
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .reduce("", String::concat);
        JsonNode assignments = JSON.readTree(text).path("assignments");
        if (!assignments.isArray()) {
            log.warn("AI categorization returned no assignments array");
            return null;
        }
        Map<Integer, Integer> answers = new HashMap<>();
        for (JsonNode node : assignments) {
            JsonNode item = node.path("item");
            JsonNode category = node.path("category");
            if (item.isIntegralNumber() && category.isIntegralNumber()) {
                answers.put(item.intValue(), category.intValue());
            }
        }
        return answers;
    }

    private static String userMessage(List<Merchant> items, List<CategoryOption> categories) {
        List<Map<String, Object>> categoryList = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            categoryList.add(Map.of("index", i, "name", categories.get(i).name()));
        }
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String description = items.get(i).description();
            if (description.length() > MAX_DESCRIPTION_CHARS) {
                int end = MAX_DESCRIPTION_CHARS;
                if (Character.isHighSurrogate(description.charAt(end - 1))) {
                    end--; // do not leave a lone high surrogate: the API rejects it
                }
                description = description.substring(0, end);
            }
            itemList.add(Map.of("index", i, "description", description));
        }
        try {
            return JSON.writeValueAsString(Map.of("categories", categoryList, "items", itemList));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonOutputFormat.Schema schema() {
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("type", "object");
        assignment.put(
                "properties",
                Map.of(
                        "item",
                        Map.of("type", "integer"),
                        "category",
                        Map.of("anyOf", List.of(Map.of("type", "integer"), Map.of("type", "null")))));
        assignment.put("required", List.of("item", "category"));
        assignment.put("additionalProperties", false);
        return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty(
                        "properties",
                        JsonValue.from(Map.of("assignments", Map.of("type", "array", "items", assignment))))
                .putAdditionalProperty("required", JsonValue.from(List.of("assignments")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    private static UUID hashOf(List<CategoryOption> categories) {
        String joined =
                categories.stream().map(c -> c.id() + ":" + c.name()).sorted().reduce("", (a, b) -> a + "\n" + b);
        return UUID.nameUUIDFromBytes(joined.getBytes(StandardCharsets.UTF_8));
    }

    /** Class and status only: the message may echo request content. */
    private static void logFailure(Exception e) {
        if (e instanceof AnthropicServiceException service) {
            log.warn("AI categorization failed: {} status={}", e.getClass().getSimpleName(), service.statusCode());
        } else {
            log.warn("AI categorization failed: {}", e.getClass().getSimpleName());
        }
    }
}
