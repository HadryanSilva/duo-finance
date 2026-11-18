package br.dev.duofinance.imports.ai;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.imports.ai.MerchantCategorizer.CategoryOption;
import br.dev.duofinance.imports.ai.MerchantCategorizer.Merchant;
import br.dev.duofinance.shared.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClaudeMerchantCategorizerTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final UUID account = UUID.randomUUID();
    private final CategoryOption food = new CategoryOption(UUID.randomUUID(), "Food");
    private final CategoryOption transport = new CategoryOption(UUID.randomUUID(), "Transport");
    private final List<CategoryOption> categories = List.of(food, transport);

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> responseText = new AtomicReference<>();
    private final AtomicReference<String> stopReason = new AtomicReference<>("end_turn");
    private volatile int status = 200;
    private volatile long delayMs = 0;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-18T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    // client gave up
                }
            }
            String json = status == 200
                    ? message(responseText.get(), stopReason.get())
                    : "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}";
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, out.length);
                exchange.getResponseBody().write(out);
            } catch (java.io.IOException ignored) {
                // client timed out and closed the connection
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String message(String text, String stopReason) {
        String escaped = text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + escaped + "\"}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
    }

    private ClaudeMerchantCategorizer categorizer(Duration timeout) {
        var props = new AppProperties.Ai(
                "test-key", "claude-haiku-5-5", timeout, 150, "http://127.0.0.1:" + server.getAddress().getPort());
        return new ClaudeMerchantCategorizer(props, clock);
    }

    private ClaudeMerchantCategorizer categorizer() {
        return categorizer(Duration.ofSeconds(5));
    }

    private static Merchant merchant(String key, String description) {
        return new Merchant(key, description);
    }

    @Test
    void sendsOnlyIndexesDescriptionsAndCategoryNames() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":0}]}");

        categorizer().categorize(account, List.of(merchant("uber trip", "Uber *Trip")), categories);

        String body = bodies.getFirst();
        assertThat(body).contains("Uber *Trip").contains("Food").contains("Transport");
        assertThat(UUID_PATTERN.matcher(body).find()).isFalse();
        assertThat(body).contains("\"model\":\"claude-haiku-5-5\"");
        assertThat(body).doesNotContain("temperature").doesNotContain("top_p");
    }

    @Test
    void schemaUsesOnlyTheDocumentedStructuredOutputSubset() throws Exception {
        responseText.set("{\"assignments\":[]}");

        categorizer().categorize(account, List.of(merchant("a", "A")), categories);

        String body = bodies.getFirst();
        assertThat(body).doesNotContainPattern("\"type\"\\s*:\\s*\\[");
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body)
                .path("output_config")
                .path("format")
                .path("schema");
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").toString()).isEqualTo("[\"assignments\"]");
        var assignment = schema.path("properties").path("assignments").path("items");
        assertThat(assignment.path("additionalProperties").isBoolean()).isTrue();
        assertThat(assignment.path("additionalProperties").asBoolean()).isFalse();
        assertThat(assignment.path("required").toString()).isEqualTo("[\"item\",\"category\"]");
        var anyOf = assignment.path("properties").path("category").path("anyOf");
        assertThat(anyOf.toString()).isEqualTo("[{\"type\":\"integer\"},{\"type\":\"null\"}]");
    }

    @Test
    void truncatesDescriptionTo100Chars() {
        responseText.set("{\"assignments\":[]}");
        String longDescription = "A".repeat(100) + "TAILMARKER";

        categorizer().categorize(account, List.of(merchant("k", longDescription)), categories);

        assertThat(bodies.getFirst()).contains("A".repeat(100)).doesNotContain("TAILMARKER");
    }

    @Test
    void mapsIndexesBackToUuids() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":1},{\"item\":1,\"category\":0}]}");

        Map<String, UUID> result =
                categorizer().categorize(account, List.of(merchant("a", "Bus"), merchant("b", "Pizza")), categories);

        assertThat(result).containsEntry("a", transport.id()).containsEntry("b", food.id());
    }

    @Test
    void ignoresOutOfRangeAndNullIndexes() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":9},{\"item\":1,\"category\":null},"
                + "{\"item\":7,\"category\":0},{\"item\":2,\"category\":-1},{\"item\":3,\"category\":0}]}");

        Map<String, UUID> result = categorizer()
                .categorize(
                        account,
                        List.of(merchant("a", "A"), merchant("b", "B"), merchant("c", "C"), merchant("d", "D")),
                        categories);

        assertThat(result).containsOnlyKeys("d").containsEntry("d", food.id());
    }

    @Test
    void invalidJson_returnsEmpty() {
        responseText.set("this is not json");

        assertThat(categorizer().categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();
    }

    @Test
    void http500_returnsEmpty_noRetry() {
        status = 500;

        assertThat(categorizer().categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void slowServer_returnsEmptyWithinTimeout() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":0}]}");
        delayMs = 3000;
        var categorizer = categorizer(Duration.ofSeconds(1));

        long start = System.nanoTime();
        Map<String, UUID> result = categorizer.categorize(account, List.of(merchant("a", "A")), categories);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEmpty();
        assertThat(elapsedMs).isLessThan(2000);
    }

    @Test
    void refusalOrMaxTokens_returnsEmpty() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":0}]}");
        var categorizer = categorizer();

        stopReason.set("refusal");
        assertThat(categorizer.categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();
        stopReason.set("max_tokens");
        assertThat(categorizer.categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();
    }

    @Test
    void secondCallSameKeys_servedFromCache() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":0}]}");
        var categorizer = categorizer();

        var first = categorizer.categorize(account, List.of(merchant("a", "A")), categories);
        var second = categorizer.categorize(account, List.of(merchant("a", "A")), categories);

        assertThat(first).isEqualTo(second).containsEntry("a", food.id());
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void cacheKeyIncludesCategoryList() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":0}]}");
        var categorizer = categorizer();

        categorizer.categorize(account, List.of(merchant("a", "A")), categories);
        categorizer.categorize(
                account,
                List.of(merchant("a", "A")),
                List.of(food, transport, new CategoryOption(UUID.randomUUID(), "Fun")));

        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void cacheStoresNoAnswerToo() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":null}]}");
        var categorizer = categorizer();

        assertThat(categorizer.categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();
        assertThat(categorizer.categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();

        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void onlyMissingKeysGoToTheApi_andCacheExpiresAfterOneHour() {
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":0}]}");
        var categorizer = categorizer();

        categorizer.categorize(account, List.of(merchant("a", "Alpha")), categories);
        responseText.set("{\"assignments\":[{\"item\":0,\"category\":1}]}");
        var result =
                categorizer.categorize(account, List.of(merchant("a", "Alpha"), merchant("b", "Beta")), categories);

        assertThat(result).containsEntry("a", food.id()).containsEntry("b", transport.id());
        assertThat(bodies.get(1)).contains("Beta").doesNotContain("Alpha");

        now.set(now.get().plus(Duration.ofMinutes(61)));
        categorizer.categorize(account, List.of(merchant("a", "Alpha")), categories);
        assertThat(requests.get()).isEqualTo(3);
    }

    @Test
    void noOp_returnsEmpty() {
        assertThat(new NoOpMerchantCategorizer().categorize(account, List.of(merchant("a", "A")), categories))
                .isEmpty();
    }
}
