package br.com.hadryan.duo.finance;

import br.com.hadryan.duo.finance.auth.RefreshTokenRepository;
import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.couple.CoupleRepository;
import br.com.hadryan.duo.finance.transaction.TransactionRepository;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@ActiveProfiles("test")
@DisabledInAotMode
public abstract class BaseIntegrationTest {

    // ── Container compartilhado ───────────────────────────────────────────────
    // Padrão Singleton: o container sobe uma vez e é reusado por todos os testes
    // Evita que cada classe suba seu próprio PostgreSQL

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      SharedPostgresContainer.INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedPostgresContainer.INSTANCE::getUsername);
        registry.add("spring.datasource.password", SharedPostgresContainer.INSTANCE::getPassword);
    }

    // ── Injeções ──────────────────────────────────────────────────────────────

    @Autowired protected WebApplicationContext  wac;
    @Autowired protected ObjectMapper           objectMapper;
    @Autowired protected UserRepository         userRepository;
    @Autowired protected CoupleRepository       coupleRepository;
    @Autowired protected TransactionRepository  transactionRepository;
    @Autowired protected RefreshTokenRepository refreshTokenRepository;

    protected MockMvc mockMvc;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUpMockMvcAndCleanDatabase() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(springSecurity())
                .build();

        refreshTokenRepository.deleteAll();
        transactionRepository.deleteAll();
        userRepository.deleteAll();
        coupleRepository.deleteAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    protected String registerAndGetToken(String firstName, String lastName,
                                         String email, String password) throws Exception {
        var request = new AuthDtos.RegisterRequest(firstName, lastName, email, password);

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("accessToken").asText();
    }

    protected String loginAndGetToken(String email, String password) throws Exception {
        var request = new AuthDtos.LoginRequest(email, password);

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("accessToken").asText();
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected User findUser(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }
}