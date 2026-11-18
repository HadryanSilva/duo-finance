package br.dev.duofinance.imports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.RecordingMerchantCategorizer;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
@TestPropertySource(properties = "app.ai.max-items=2")
class ImportPreviewMaxItemsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    RecordingMerchantCategorizer categorizer;

    @Test
    void preview_respectsMaxItems() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        fixtures.category(s, "Mercearia", CategoryKind.EXPENSE);

        mvc.post()
                .uri("/api/v1/imports/revolut/preview")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile(
                        "file",
                        "statement.csv",
                        "text/csv",
                        Files.readAllBytes(Path.of("src/test/resources/imports/revolut-sample.csv"))))
                .file(new MockMultipartFile(
                        "bankAccountId", "", "text/plain", bank.toString().getBytes()))
                .exchange();

        assertThat(categorizer.calls()).hasSize(1);
        assertThat(categorizer.calls().get(0).merchants())
                .extracting(m -> m.description())
                .containsExactly("Cafe Aurora", "Mercado Lua, Lda");
    }
}
