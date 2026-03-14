package br.com.hadryan.duo.finance.couple;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CoupleControllerIT extends BaseIntegrationTest {

    // ── POST /api/couples ─────────────────────────────────────────────────────

    @Test
    void create_deveRetornar201ComDadosDoCouple() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        var request = new CoupleDtos.CreateCoupleRequest("Casal Silva");

        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Casal Silva"))
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.waitingForPartner").value(true));
    }

    @Test
    void create_semAutenticacao_deveRetornar401() throws Exception {
        var request = new CoupleDtos.CreateCoupleRequest("Casal Silva");

        mockMvc.perform(post("/api/couples")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_usuarioJaPossuiCouple_deveRetornar400() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        var request = new CoupleDtos.CreateCoupleRequest("Casal Silva");

        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/couples")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/couples/me ───────────────────────────────────────────────────

    @Test
    void findMine_deveRetornarDadosDoCouple() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");

        mockMvc.perform(get("/api/couples/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Casal Silva"))
                .andExpect(jsonPath("$.members[0].email").value("joao@test.com"));
    }

    @Test
    void findMine_semCouple_deveRetornar400() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/couples/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /api/couples/me ───────────────────────────────────────────────────

    @Test
    void update_deveAlterarNomeDoCouple() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");

        var request = new CoupleDtos.UpdateCoupleRequest("Novo Nome");

        mockMvc.perform(put("/api/couples/me")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Novo Nome"));
    }

    // ── POST /api/couples/invite ──────────────────────────────────────────────

    @Test
    void invite_deveRetornar200ComMensagem() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");

        // Registra o parceiro para que o e-mail exista no banco
        registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");

        var request = new CoupleDtos.InvitePartnerRequest("maria@test.com");

        mockMvc.perform(post("/api/couples/invite")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void invite_membroJaExistente_deveRetornar400() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        criarCouple(token, "Casal Silva");

        // Tenta convidar a si mesmo
        var request = new CoupleDtos.InvitePartnerRequest("joao@test.com");

        mockMvc.perform(post("/api/couples/invite")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/couples/join/{token} ────────────────────────────────────────

    @Test
    void join_comTokenValido_deveVincularParceiro() throws Exception {
        String tokenJoao  = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        String tokenMaria = registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");
        criarCouple(tokenJoao, "Casal Silva");

        // Gera o invite token diretamente no banco
        String inviteToken = gerarInviteToken(tokenJoao, "maria@test.com");

        mockMvc.perform(post("/api/couples/join/" + inviteToken)
                        .header("Authorization", bearer(tokenMaria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couple.members.length()").value(2))
                .andExpect(jsonPath("$.couple.waitingForPartner").value(false));
    }

    @Test
    void join_comTokenInvalido_deveRetornar404() throws Exception {
        String token = registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");

        mockMvc.perform(post("/api/couples/join/token-inexistente")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void join_usuarioJaPossuiCouple_deveRetornar400() throws Exception {
        String tokenJoao  = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");
        String tokenMaria = registerAndGetToken("Maria", "Silva", "maria@test.com", "senha123");
        criarCouple(tokenJoao, "Casal Silva");
        criarCouple(tokenMaria, "Casal Maria");

        String inviteToken = gerarInviteToken(tokenJoao, "maria@test.com");

        mockMvc.perform(post("/api/couples/join/" + inviteToken)
                        .header("Authorization", bearer(tokenMaria)))
                .andExpect(status().isBadRequest());
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

    /**
     * Dispara o invite via API e extrai o token diretamente do banco,
     * evitando dependência do envio de e-mail nos testes.
     */
    private String gerarInviteToken(String token, String partnerEmail) throws Exception {
        var request = new CoupleDtos.InvitePartnerRequest(partnerEmail);
        mockMvc.perform(post("/api/couples/invite")
                .header("Authorization", bearer(token))
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // Lê o token diretamente do banco — independente do e-mail
        return coupleRepository.findAll().stream()
                .filter(c -> c.getInviteToken() != null)
                .findFirst()
                .orElseThrow()
                .getInviteToken();
    }
}