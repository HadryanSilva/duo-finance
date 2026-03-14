package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.BaseIntegrationTest;
import br.com.hadryan.duo.finance.user.dto.UserDtos;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllerIT extends BaseIntegrationTest {

    // ── GET /api/users/me ─────────────────────────────────────────────────────

    @Test
    void me_autenticado_deveRetornarDadosDoUsuario() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("joao@test.com"))
                .andExpect(jsonPath("$.firstName").value("João"))
                .andExpect(jsonPath("$.lastName").value("Silva"))
                .andExpect(jsonPath("$.coupleId").isEmpty());
    }

    @Test
    void me_semAutenticacao_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── PATCH /api/users/me ───────────────────────────────────────────────────

    @Test
    void updateProfile_deveAlterarNome() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        var request = new UserDtos.UpdateProfileRequest("Carlos", "Souza");

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Carlos"))
                .andExpect(jsonPath("$.lastName").value("Souza"));
    }

    @Test
    void updateProfile_comDadosInvalidos_deveRetornar400() throws Exception {
        String token = registerAndGetToken("João", "Silva", "joao@test.com", "senha123");

        var request = new UserDtos.UpdateProfileRequest("", "");

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}