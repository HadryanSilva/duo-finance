package br.com.hadryan.duo.finance.notification;

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

class NotificationControllerIT extends BaseIntegrationTest {

    private String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");
    }

    // ── GET /api/notifications ────────────────────────────────────────────────

    @Test
    void list_semNotificacoes_deveRetornarListaVazia() throws Exception {
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications").isArray())
                .andExpect(jsonPath("$.notifications.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void list_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }

    // ── Notificação de meta — gerada indiretamente ────────────────────────────

    @Test
    void notificacao_metaWarning_deveSerCriada() throws Exception {
        // Cria meta de R$500 e gasta R$450 (90% → WARNING)
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("450.00"), "Supermercado");

        // Aguarda o listener assíncrono processar
        Thread.sleep(500);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("GOAL_WARNING"))
                .andExpect(jsonPath("$.notifications[0].read").value(false));
    }

    @Test
    void notificacao_metaExceeded_deveSerCriada() throws Exception {
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("600.00"), "Supermercado");

        Thread.sleep(500);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications[0].type").value("GOAL_EXCEEDED"));
    }

    // ── PATCH /api/notifications/{id}/read ───────────────────────────────────

    @Test
    void markAsRead_deveMarcaNotificacaoComoLida() throws Exception {
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("450.00"), "Supermercado");
        Thread.sleep(500);

        // Obtém o ID da notificação
        MvcResult list = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andReturn();

        String id = objectMapper.readTree(list.getResponse().getContentAsString())
                .get("notifications").get(0).get("id").asText();

        mockMvc.perform(patch("/api/notifications/" + id + "/read")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        // Confirma que está lida e unreadCount = 0
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.notifications[0].read").value(true));
    }

    // ── PATCH /api/notifications/read-all ────────────────────────────────────

    @Test
    void markAllAsRead_deveMarcaTodasComoLidas() throws Exception {
        criarMeta(token, TransactionCategory.FOOD,      new BigDecimal("500.00"));
        criarMeta(token, TransactionCategory.TRANSPORT, new BigDecimal("300.00"));
        criarTransacao(token, TransactionCategory.FOOD,      new BigDecimal("450.00"), "Super");
        criarTransacao(token, TransactionCategory.TRANSPORT, new BigDecimal("280.00"), "Uber");
        Thread.sleep(500);

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    // ── GET /api/notifications/settings ──────────────────────────────────────

    @Test
    void getSettings_padrao_deveRetornarEnabled() throws Exception {
        mockMvc.perform(get("/api/notifications/settings")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // ── PATCH /api/notifications/settings/toggle ──────────────────────────────

    @Test
    void toggleSettings_deveAlternarEstado() throws Exception {
        // Desativa
        mockMvc.perform(patch("/api/notifications/settings/toggle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Reativa
        mockMvc.perform(patch("/api/notifications/settings/toggle")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void notificacao_comSettingsDesativado_naoDeveSerCriada() throws Exception {
        // Desativa notificações
        mockMvc.perform(patch("/api/notifications/settings/toggle")
                .header("Authorization", bearer(token)));

        // Gera evento que normalmente criaria notificação
        criarMeta(token, TransactionCategory.FOOD, new BigDecimal("500.00"));
        criarTransacao(token, TransactionCategory.FOOD, new BigDecimal("450.00"), "Supermercado");
        Thread.sleep(500);

        // Não deve ter nenhuma notificação
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.unreadCount").value(0));
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