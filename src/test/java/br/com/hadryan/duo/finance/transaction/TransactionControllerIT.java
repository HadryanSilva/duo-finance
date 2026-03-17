package br.com.hadryan.duo.finance.transaction;

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

class TransactionControllerIT extends BaseIntegrationTest {

    private String tokenJoao;
    private String tokenMaria;

    @BeforeEach
    void setup() throws Exception {
        tokenJoao  = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        tokenMaria = registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");

        criarCouple(tokenJoao, "Casal Silva");
        String inviteToken = gerarInviteToken(tokenJoao, "maria@test.com");
        aceitarConvite(tokenMaria, inviteToken);
    }

    // ── POST /api/transactions ────────────────────────────────────────────────

    @Test
    void create_deveRetornar201ComTransacaoCriada() throws Exception {
        var request = buildRequest(TransactionCategory.FOOD, new BigDecimal("150.00"),
                "Supermercado", LocalDate.now(), false, null, null);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(tokenJoao))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.description").value("Supermercado"))
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.createdBy.firstName").value("João"));
    }

    @Test
    void create_transacaoRecorrente_deveRetornar201() throws Exception {
        var request = buildRequest(TransactionCategory.HOUSING, new BigDecimal("1200.00"),
                "Aluguel", LocalDate.now(), true, "MONTHLY", null);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(tokenJoao))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recurring").value(true))
                .andExpect(jsonPath("$.recurrenceRule").value("MONTHLY"));
    }

    @Test
    void create_semAutenticacao_deveRetornar401() throws Exception {
        var request = buildRequest(TransactionCategory.FOOD, new BigDecimal("50.00"),
                null, LocalDate.now(), false, null, null);

        mockMvc.perform(post("/api/transactions")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_semCouple_deveRetornar400() throws Exception {
        String tokenSolo = registerAndGetToken("Pedro", "Sem", "pedro@test.com", "senha123");

        var request = buildRequest(TransactionCategory.FOOD, new BigDecimal("50.00"),
                null, LocalDate.now(), false, null, null);

        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(tokenSolo))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/transactions ─────────────────────────────────────────────────

    @Test
    void findAll_deveRetornarTransacoesDoCouple() throws Exception {
        criarTransacao(tokenJoao, TransactionCategory.FOOD, new BigDecimal("100.00"), "Mercado");
        criarTransacao(tokenMaria, TransactionCategory.TRANSPORT, new BigDecimal("50.00"), "Uber");

        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void findAll_filtroPorTipo_deveRetornarApenasDoTipo() throws Exception {
        criarTransacao(tokenJoao, TransactionCategory.FOOD, new BigDecimal("100.00"), "Mercado");
        criarTransacao(tokenJoao, TransactionCategory.SALARY, new BigDecimal("5000.00"), "Salário");

        mockMvc.perform(get("/api/transactions?type=EXPENSE")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("EXPENSE"));
    }

    @Test
    void findAll_filtroPorDescricao_deveRetornarCorrespondentes() throws Exception {
        criarTransacao(tokenJoao, TransactionCategory.FOOD, new BigDecimal("80.00"), "Supermercado Extra");
        criarTransacao(tokenJoao, TransactionCategory.FOOD, new BigDecimal("40.00"), "Padaria");

        mockMvc.perform(get("/api/transactions?description=supermercado")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Supermercado Extra"));
    }

    @Test
    void findAll_filtroPorParceiro_deveRetornarApenasDoUsuario() throws Exception {
        criarTransacao(tokenJoao, TransactionCategory.FOOD, new BigDecimal("100.00"), "João comprou");
        criarTransacao(tokenMaria, TransactionCategory.FOOD, new BigDecimal("80.00"), "Maria comprou");

        String joaoId = objectMapper.readTree(
                mockMvc.perform(get("/api/users/me")
                                .header("Authorization", bearer(tokenJoao)))
                        .andReturn().getResponse().getContentAsString()
        ).get("id").asText();

        mockMvc.perform(get("/api/transactions?userId=" + joaoId)
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].description").value("João comprou"));
    }

    // ── GET /api/transactions/{id} ────────────────────────────────────────────

    @Test
    void findById_deveRetornarTransacaoCorreta() throws Exception {
        String id = criarTransacaoEObterID(tokenJoao, TransactionCategory.FOOD,
                new BigDecimal("100.00"), "Mercado");

        mockMvc.perform(get("/api/transactions/" + id)
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.description").value("Mercado"));
    }

    @Test
    void findById_idInexistente_deveRetornar404() throws Exception {
        mockMvc.perform(get("/api/transactions/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/transactions/{id} ────────────────────────────────────────────

    @Test
    void update_deveAlterarTransacao() throws Exception {
        String id = criarTransacaoEObterID(tokenJoao, TransactionCategory.FOOD,
                new BigDecimal("100.00"), "Mercado");

        // UpdateTransactionRequest agora tem customCategoryId como 2º parâmetro (null = categoria do sistema)
        var update = new TransactionDtos.UpdateTransactionRequest(
                TransactionCategory.LEISURE, null, new BigDecimal("200.00"), "Cinema", LocalDate.now()
        );

        mockMvc.perform(put("/api/transactions/" + id)
                        .header("Authorization", bearer(tokenJoao))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Cinema"))
                .andExpect(jsonPath("$.amount").value(200.00))
                .andExpect(jsonPath("$.category").value("LEISURE"));
    }

    @Test
    void update_transacaoDeOutroCouple_deveRetornar404() throws Exception {
        String tokenOutro = registerAndGetToken("Outro", "User", "outro@test.com", "senha123");
        criarCouple(tokenOutro, "Casal Outro");

        String id = criarTransacaoEObterID(tokenOutro, TransactionCategory.FOOD,
                new BigDecimal("100.00"), "Transação do outro");

        var update = new TransactionDtos.UpdateTransactionRequest(
                TransactionCategory.FOOD, null, new BigDecimal("50.00"), "Tentativa", LocalDate.now()
        );

        mockMvc.perform(put("/api/transactions/" + id)
                        .header("Authorization", bearer(tokenJoao))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/transactions/{id} ─────────────────────────────────────────

    @Test
    void delete_deveFazerSoftDelete() throws Exception {
        String id = criarTransacaoEObterID(tokenJoao, TransactionCategory.FOOD,
                new BigDecimal("100.00"), "Mercado");

        mockMvc.perform(delete("/api/transactions/" + id)
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void delete_idInexistente_deveRetornar404() throws Exception {
        mockMvc.perform(delete("/api/transactions/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", bearer(tokenJoao)))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Constrói um CreateTransactionRequest com categoria do sistema (enum).
     * customCategoryId = null indica categoria fixa.
     */
    private TransactionDtos.CreateTransactionRequest buildRequest(
            TransactionCategory category, BigDecimal amount, String description,
            LocalDate date, boolean recurring, String recurrenceRule, LocalDate recurrenceEndDate) {
        return new TransactionDtos.CreateTransactionRequest(
                category,
                null,   // customCategoryId — null = categoria do sistema
                amount,
                description,
                date,
                recurring,
                recurrenceRule != null
                        ? br.com.hadryan.duo.finance.transaction.enums.RecurrenceRule.valueOf(recurrenceRule)
                        : null,
                recurrenceEndDate
        );
    }

    private void criarTransacao(String token, TransactionCategory category,
                                BigDecimal amount, String description) throws Exception {
        var request = buildRequest(category, amount, description, LocalDate.now(), false, null, null);
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private String criarTransacaoEObterID(String token, TransactionCategory category,
                                          BigDecimal amount, String description) throws Exception {
        var request = buildRequest(category, amount, description, LocalDate.now(), false, null, null);
        String body = mockMvc.perform(post("/api/transactions")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void criarCouple(String token, String name) throws Exception {
        var request = new CoupleDtos.CreateCoupleRequest(name);
        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private String gerarInviteToken(String token, String partnerEmail) throws Exception {
        var request = new CoupleDtos.InvitePartnerRequest(partnerEmail);
        mockMvc.perform(post("/api/couples/invite")
                .header("Authorization", bearer(token))
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
        return coupleRepository.findAll().stream()
                .filter(c -> c.getInviteToken() != null)
                .findFirst().orElseThrow().getInviteToken();
    }

    private void aceitarConvite(String token, String inviteToken) throws Exception {
        mockMvc.perform(post("/api/couples/join/" + inviteToken)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }
}