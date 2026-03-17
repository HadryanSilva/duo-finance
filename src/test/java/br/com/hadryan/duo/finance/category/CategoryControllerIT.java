package br.com.hadryan.duo.finance.category;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.category.dto.CustomCategoryDtos;
import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CategoryControllerIT extends BaseIntegrationTest {

    // ── GET /api/categories — usuário sem casal (sem customizadas) ────────────

    @Test
    void list_semFiltro_deveRetornarTodasAsCategorias() throws Exception {
        // Usuário sem casal → nenhuma categoria personalizada → apenas as 18 do sistema
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/categories")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(18)); // 12 despesas + 6 receitas
    }

    @Test
    void list_filtroExpense_deveRetornarApenasDesp() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/categories?type=EXPENSE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].type").value("EXPENSE"));
    }

    @Test
    void list_filtroIncome_deveRetornarApenasReceitas() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/categories?type=INCOME")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].type").value("INCOME"));
    }

    @Test
    void list_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_comCategoriaCustomizada_deveIncluirNaListagem() throws Exception {
        // Usuário com casal e categoria customizada → total = 18 sistema + 1 customizada
        String tokenJoao  = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        String tokenMaria = registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");

        criarCouple(tokenJoao, "Casal Silva");

        // Envia convite para gerar o token antes de buscá-lo
        var inviteRequest = new CoupleDtos.InvitePartnerRequest("maria@test.com");
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/couples/invite")
                .header("Authorization", bearer(tokenJoao))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inviteRequest)));

        String inviteToken = coupleRepository.findAll().stream()
                .filter(c -> c.getInviteToken() != null).findFirst().orElseThrow().getInviteToken();
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/couples/join/" + inviteToken)
                .header("Authorization", bearer(tokenMaria)));

        // Cria uma categoria personalizada
        var payload = new CustomCategoryDtos.CreateRequest(
                "Viagem", TransactionType.EXPENSE, "pi pi-plane"
        );
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/custom-categories")
                .header("Authorization", bearer(tokenJoao))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));

        mockMvc.perform(get("/api/categories")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(19)) // 18 sistema + 1 customizada
                .andExpect(jsonPath("$[?(@.custom == true)].label").value("Viagem"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void criarCouple(String token, String name) throws Exception {
        var request = new CoupleDtos.CreateCoupleRequest(name);
        mockMvc.perform(MockMvcRequestBuilders
                .post("/api/couples")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }
}