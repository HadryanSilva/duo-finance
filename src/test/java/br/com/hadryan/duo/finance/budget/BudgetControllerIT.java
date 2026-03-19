package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BudgetControllerIT extends BaseIntegrationTest {

    private String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");
    }

    // ── PUT /api/budget/income ────────────────────────────────────────────────

    @Test
    void setIncome_deveDefinirRenda() throws Exception {
        mockMvc.perform(put("/api/budget/income")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "monthlyIncome": 5000.00 }
                        """))
                .andExpect(status().isNoContent());
    }

    @Test
    void setIncome_valorZero_deveRetornar400() throws Exception {
        mockMvc.perform(put("/api/budget/income")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "monthlyIncome": 0 }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setIncome_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(put("/api/budget/income")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            { "monthlyIncome": 5000.00 }
                        """))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/budget/income ─────────────────────────────────────────────

    @Test
    void removeIncome_deveRemoverRenda() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));

        mockMvc.perform(delete("/api/budget/income")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        // Overview deve retornar monthlyIncome null
        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyIncome").isEmpty());
    }

    // ── PUT /api/budget ───────────────────────────────────────────────────────

    @Test
    void saveBudget_deveAlocarCategorias() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));

        var body = Map.of("allocations", List.of(
                Map.of("category", "FOOD",      "percentage", 30),
                Map.of("category", "TRANSPORT", "percentage", 15)
        ));

        mockMvc.perform(put("/api/budget")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].percentage").value(30.0))
                .andExpect(jsonPath("$[0].allocated").value(1500.00));
    }

    @Test
    void saveBudget_somaMaisDe100_deveRetornar400() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));

        var body = Map.of("allocations", List.of(
                Map.of("category", "FOOD",      "percentage", 60),
                Map.of("category", "TRANSPORT", "percentage", 50)
        ));

        mockMvc.perform(put("/api/budget")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveBudget_semRenda_deveRetornar400() throws Exception {
        var body = Map.of("allocations", List.of(
                Map.of("category", "FOOD", "percentage", 30)
        ));

        mockMvc.perform(put("/api/budget")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/budget/overview ──────────────────────────────────────────────

    @Test
    void overview_semRendaEAlocacoes_deveRetornarZerado() throws Exception {
        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyIncome").isEmpty())
                .andExpect(jsonPath("$.totalAllocated").value(0))
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void overview_comRendaEAlocacoes_deveCalcularValores() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD", 30);

        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyIncome").value(5000.00))
                .andExpect(jsonPath("$.totalAllocatedPct").value(30.0))
                .andExpect(jsonPath("$.totalAllocated").value(1500.00))
                .andExpect(jsonPath("$.categories[0].category").value("FOOD"))
                .andExpect(jsonPath("$.categories[0].percentage").value(30.0))
                .andExpect(jsonPath("$.categories[0].allocated").value(1500.00));
    }

    @Test
    void overview_comGastos_deveCalcularRealizado() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD", 30); // alocado: R$ 1500
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("800.00"), "Supermercado");

        // 800 / 1500 = 53% -> status OK
        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpent").value(800.00))
                .andExpect(jsonPath("$.categories[0].spent").value(800.00))
                .andExpect(jsonPath("$.categories[0].status").value("OK"));
    }

    @Test
    void overview_comGastosAcima80_deveRetornarWarning() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD", 30); // alocado: R$ 1500
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("1300.00"), "Supermercado");

        // 1300 / 1500 = 86% -> WARNING
        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].status").value("WARNING"));
    }

    @Test
    void overview_comGastosAcima100_deveRetornarExceeded() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD", 30); // alocado: R$ 1500
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("1600.00"), "Supermercado");

        // 1600 / 1500 = 106% -> EXCEEDED
        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].status").value("EXCEEDED"))
                .andExpect(jsonPath("$.totalRemaining").value(-100.00));
    }

    @Test
    void overview_semCouple_deveRetornar400() throws Exception {
        String tokenSolo = registerAndGetToken("Pedro", "Solo", "pedro@test.com", "senha123");

        mockMvc.perform(get("/api/budget/overview")
                        .header("Authorization", bearer(tokenSolo)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/budget/allocations ───────────────────────────────────────────

    @Test
    void listAllocations_semAlocacoes_deveRetornarListaVazia() throws Exception {
        mockMvc.perform(get("/api/budget/allocations")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listAllocations_deveRetornarAlocacoesComValores() throws Exception {
        definirRenda(token, new BigDecimal("4000.00"));
        alocarCategoria(token, "TRANSPORT", 25);

        mockMvc.perform(get("/api/budget/allocations")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("TRANSPORT"))
                .andExpect(jsonPath("$[0].percentage").value(25.0))
                .andExpect(jsonPath("$[0].allocated").value(1000.00));
    }

    // ── DELETE /api/budget/category/{category} ────────────────────────────────

    @Test
    void deleteCategory_deveRemoverAlocacao() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD", 30);

        mockMvc.perform(delete("/api/budget/category/FOOD")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/budget/allocations")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── DELETE /api/budget ────────────────────────────────────────────────────

    @Test
    void clearAll_deveLimparTodoOOrcamento() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD",      30);
        alocarCategoria(token, "TRANSPORT", 15);

        mockMvc.perform(delete("/api/budget")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/budget/allocations")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /api/budget/comparison ────────────────────────────────────────────

    @Test
    void comparison_deveRetornarHistoricoMensal() throws Exception {
        definirRenda(token, new BigDecimal("5000.00"));
        alocarCategoria(token, "FOOD", 30);

        mockMvc.perform(get("/api/budget/comparison?months=3")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyIncome").value(5000.00))
                .andExpect(jsonPath("$.months").isArray())
                .andExpect(jsonPath("$.months.length()").value(3));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void criarCouple(String token, String name) throws Exception {
        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CoupleDtos.CreateCoupleRequest(name))))
                .andExpect(status().isCreated());
    }

    private void definirRenda(String token, BigDecimal value) throws Exception {
        mockMvc.perform(put("/api/budget/income")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("monthlyIncome", value))))
                .andExpect(status().isNoContent());
    }

    private void alocarCategoria(String token, String category, int percentage) throws Exception {
        var body = Map.of("allocations", List.of(
                Map.of("category", category, "percentage", percentage)
        ));
        mockMvc.perform(put("/api/budget")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void criarTransacao(String token, TransactionCategory category,
                                BigDecimal amount, String description) throws Exception {
        var request = new TransactionDtos.CreateTransactionRequest(
                category, null, amount, description, LocalDate.now(), false, null, null);
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}