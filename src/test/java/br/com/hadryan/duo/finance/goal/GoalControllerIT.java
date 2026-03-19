package br.com.hadryan.duo.finance.goal;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
import br.com.hadryan.duo.finance.transaction.dto.TransactionDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GoalControllerIT extends BaseIntegrationTest {

    private String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");
    }

    // ── POST /api/goals ───────────────────────────────────────────────────────

    @Test
    void create_deveRetornar201ComMeta() throws Exception {
        var request = new GoalDtos.CreateGoalRequest(TransactionCategory.FOOD, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/goals")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.monthlyLimit").value(500.00))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_semCouple_deveRetornar400() throws Exception {
        String tokenSolo = registerAndGetToken("Pedro", "Solo", "pedro@test.com", "senha123");
        var request = new GoalDtos.CreateGoalRequest(TransactionCategory.FOOD, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/goals")
                        .header("Authorization", bearer(tokenSolo))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_categoriaDuplicada_deveRetornar400() throws Exception {
        var request = new GoalDtos.CreateGoalRequest(TransactionCategory.FOOD, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/goals")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Segunda vez com mesma categoria
        mockMvc.perform(post("/api/goals")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_semAutenticacao_deveRetornar401() throws Exception {
        var request = new GoalDtos.CreateGoalRequest(TransactionCategory.FOOD, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/goals")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/goals ────────────────────────────────────────────────────────

    @Test
    void listAll_deveRetornarMetasDoCouple() throws Exception {
        criarMeta(token, TransactionCategory.FOOD,      new BigDecimal("500.00"));
        criarMeta(token, TransactionCategory.TRANSPORT, new BigDecimal("300.00"));

        mockMvc.perform(get("/api/goals")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listAll_semMetas_deveRetornarListaVazia() throws Exception {
        mockMvc.perform(get("/api/goals")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── PUT /api/goals/{id} ───────────────────────────────────────────────────

    @Test
    void update_deveAlterarLimite() throws Exception {
        String id = criarMetaEObterID(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        var request = new GoalDtos.UpdateGoalRequest(new BigDecimal("800.00"));

        mockMvc.perform(put("/api/goals/" + id)
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyLimit").value(800.00));
    }

    @Test
    void update_metaDeOutroCouple_deveRetornar404() throws Exception {
        // Cria meta com token diferente
        String tokenMaria = registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");
        criarCouple(tokenMaria, "Casal Maria");
        String idMaria = criarMetaEObterID(tokenMaria, TransactionCategory.FOOD, new BigDecimal("500.00"));

        var request = new GoalDtos.UpdateGoalRequest(new BigDecimal("800.00"));

        mockMvc.perform(put("/api/goals/" + idMaria)
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ── PATCH /api/goals/{id}/toggle ──────────────────────────────────────────

    @Test
    void toggle_deveAlternarEstadoAtivo() throws Exception {
        String id = criarMetaEObterID(token, TransactionCategory.FOOD, new BigDecimal("500.00"));

        // Pausa a meta
        mockMvc.perform(patch("/api/goals/" + id + "/toggle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Reativa a meta
        mockMvc.perform(patch("/api/goals/" + id + "/toggle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    // ── DELETE /api/goals/{id} ────────────────────────────────────────────────

    @Test
    void delete_deveRemoverMeta() throws Exception {
        String id = criarMetaEObterID(token, TransactionCategory.FOOD, new BigDecimal("500.00"));

        mockMvc.perform(delete("/api/goals/" + id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        // Confirma que foi removida
        mockMvc.perform(get("/api/goals")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /api/goals/progress ───────────────────────────────────────────────

    @Test
    void progress_semGastos_deveRetornarZeroPorcento() throws Exception {
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));

        mockMvc.perform(get("/api/goals/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spent").value(0.00))
                .andExpect(jsonPath("$[0].percentage").value(0.0))
                .andExpect(jsonPath("$[0].alertLevel").value("NONE"));
    }

    @Test
    void progress_comGastosAcima80_deveRetornarWarning() throws Exception {
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        // Gasta 450 = 90% → WARNING
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("450.00"), "Supermercado");

        mockMvc.perform(get("/api/goals/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertLevel").value("WARNING"))
                .andExpect(jsonPath("$[0].spent").value(450.00));
    }

    @Test
    void progress_comGastosAcima100_deveRetornarExceeded() throws Exception {
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        // Gasta 600 = 120% → EXCEEDED
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("600.00"), "Supermercado");

        mockMvc.perform(get("/api/goals/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertLevel").value("EXCEEDED"));
    }

    @Test
    void progress_metaPausada_naoDeveAparece() throws Exception {
        String id = criarMetaEObterID(token, TransactionCategory.FOOD, new BigDecimal("500.00"));

        // Pausa a meta
        mockMvc.perform(patch("/api/goals/" + id + "/toggle")
                .header("Authorization", bearer(token)));

        // Progress só retorna metas ativas
        mockMvc.perform(get("/api/goals/progress")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void criarCouple(String token, String name) throws Exception {
        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CoupleDtos.CreateCoupleRequest(name))))
                .andExpect(status().isCreated());
    }

    private void criarMeta(String token, TransactionCategory category, BigDecimal limit) throws Exception {
        mockMvc.perform(post("/api/goals")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new GoalDtos.CreateGoalRequest(category, limit))))
                .andExpect(status().isCreated());
    }

    private String criarMetaEObterID(String token, TransactionCategory category, BigDecimal limit) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/goals")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new GoalDtos.CreateGoalRequest(category, limit))))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
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