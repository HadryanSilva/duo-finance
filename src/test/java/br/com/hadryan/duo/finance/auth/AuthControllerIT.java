package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerIT extends BaseIntegrationTest {

    // ── POST /api/auth/register ───────────────────────────────────────────────

    @Test
    void register_deveRetornar201ComTokens() throws Exception {
        var request = new AuthDtos.RegisterRequest("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("joao@test.com"))
                .andExpect(jsonPath("$.user.firstName").value("João"));
    }

    @Test
    void register_comEmailDuplicado_deveRetornar400() throws Exception {
        var request = new AuthDtos.RegisterRequest("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_comDadosInvalidos_deveRetornar400() throws Exception {
        var request = new AuthDtos.RegisterRequest("", "", "email-invalido", "123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────

    @Test
    void login_comCredenciaisValidas_deveRetornar200ComTokens() throws Exception {
        registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        var login = new AuthDtos.LoginRequest("joao@test.com", "senha123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("joao@test.com"));
    }

    @Test
    void login_comSenhaErrada_deveRetornar401() throws Exception {
        registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        var login = new AuthDtos.LoginRequest("joao@test.com", "senhaerrada");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_comEmailInexistente_deveRetornar401() throws Exception {
        var login = new AuthDtos.LoginRequest("naoexiste@test.com", "senha123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /auth/refresh ────────────────────────────────────────────────────

    @Test
    void refresh_comTokenValido_deveRetornarNovosTokens() throws Exception {
        var request = new AuthDtos.RegisterRequest("João", "Silva", "joao@test.com", "senha123");

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();

        String refreshToken = objectMapper.readTree(body).get("refreshToken").asText();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthDtos.RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void refresh_comTokenInvalido_deveRetornar401() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AuthDtos.RefreshRequest("token-invalido"))))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /auth/logout ─────────────────────────────────────────────────────

    @Test
    void logout_autenticado_deveRetornar204() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/auth/forgot-password ────────────────────────────────────────

    @Test
    void forgotPassword_sempreRetorna204_independenteDoEmail() throws Exception {
        var request = new AuthDtos.ForgotPasswordRequest("qualquer@email.com");

        // Não enumera se o e-mail existe — sempre 204
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }
}
