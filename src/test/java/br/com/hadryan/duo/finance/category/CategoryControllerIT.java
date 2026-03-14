package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CategoryControllerIT extends BaseIntegrationTest {

    // ── GET /api/categories ───────────────────────────────────────────────────

    @Test
    void list_semFiltro_deveRetornarTodasAsCategorias() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/categories")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(16)); // total de categorias no enum
    }

    @Test
    void list_filtroExpense_deveRetornarApenasDesp() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/categories?type=EXPENSE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].type").value("EXPENSE"));
    }

    @Test
    void list_filtroIncome_deveRetornarApenasReceitas() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/categories?type=INCOME")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].type").value("INCOME"));
    }

    @Test
    void list_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isUnauthorized());
    }
}