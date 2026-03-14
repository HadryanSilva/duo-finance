package br.com.hadryan.duo.finance.report;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportControllerIT extends BaseIntegrationTest {

    private String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");
        criarTransacao(token, TransactionCategory.FOOD,   new BigDecimal("300.00"), "Supermercado");
        criarTransacao(token, TransactionCategory.SALARY, new BigDecimal("5000.00"), "Salário");
    }

    // ── GET /api/reports/summary ──────────────────────────────────────────────

    @Test
    void summary_deveRetornarTotaisCorretos() throws Exception {
        mockMvc.perform(get("/api/reports/summary")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpense").value(300.00))
                .andExpect(jsonPath("$.totalIncome").value(5000.00))
                .andExpect(jsonPath("$.balance").value(4700.00))
                .andExpect(jsonPath("$.transactionCount").value(2));
    }

    @Test
    void summary_comFiltroDeData_deveRespeitar() throws Exception {
        LocalDate inicio = LocalDate.now().withDayOfMonth(1);
        LocalDate fim    = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        mockMvc.perform(get("/api/reports/summary?startDate=" + inicio + "&endDate=" + fim)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(2));
    }

    @Test
    void summary_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/reports/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void summary_semCouple_deveRetornar400() throws Exception {
        String tokenSolo = registerAndGetToken("Pedro", "Solo", "pedro@test.com", "senha123");

        mockMvc.perform(get("/api/reports/summary")
                        .header("Authorization", bearer(tokenSolo)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/reports/by-category ─────────────────────────────────────────

    @Test
    void byCategory_deveRetornarCategoriasDeDespesa() throws Exception {
        mockMvc.perform(get("/api/reports/by-category?type=EXPENSE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].category").value("FOOD"))
                .andExpect(jsonPath("$.categories[0].amount").value(300.00));
    }

    @Test
    void byCategory_income_deveRetornarCategoriasDeReceita() throws Exception {
        mockMvc.perform(get("/api/reports/by-category?type=INCOME")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].category").value("SALARY"));
    }

    // ── GET /api/reports/monthly-comparison ──────────────────────────────────

    @Test
    void monthlyComparison_deveRetornarUltimos6Meses() throws Exception {
        mockMvc.perform(get("/api/reports/monthly-comparison")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months").isArray())
                .andExpect(jsonPath("$.months.length()").value(6));
    }

    @Test
    void monthlyComparison_mesAtualDeveConterTransacoes() throws Exception {
        String body = mockMvc.perform(get("/api/reports/monthly-comparison")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // O último mês do array é o atual
        var months = objectMapper.readTree(body).get("months");
        var current = months.get(months.size() - 1);

        assert current.get("totalExpense").asDouble() == 300.00;
        assert current.get("totalIncome").asDouble()  == 5000.00;
    }

    // ── GET /api/reports/export/csv ───────────────────────────────────────────

    @Test
    void exportCsv_deveRetornarArquivoComHeader() throws Exception {
        mockMvc.perform(get("/api/reports/export/csv")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void criarCouple(String token, String name) throws Exception {
        var request = new CoupleDtos.CreateCoupleRequest(name);
        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private void criarTransacao(String token, TransactionCategory category,
                                BigDecimal amount, String description) throws Exception {
        var request = new TransactionDtos.CreateTransactionRequest(
                category, amount, description, LocalDate.now(), false, null, null
        );
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}