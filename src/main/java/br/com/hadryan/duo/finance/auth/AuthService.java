package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.auth.jwt.JwtService;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Métricas geradas:
 *   auth_login_total{result="success|failure",provider="local|google"}
 *   auth_register_total{result="success|failure"}
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository      userRepository;
    private final PasswordEncoder     passwordEncoder;
    private final JwtService          jwtService;
    private final RefreshTokenService refreshTokenService;

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter registerSuccess;
    private final Counter registerFailure;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            MeterRegistry meterRegistry
    ) {
        this.userRepository      = userRepository;
        this.passwordEncoder     = passwordEncoder;
        this.jwtService          = jwtService;
        this.refreshTokenService = refreshTokenService;

        this.loginSuccess = Counter.builder("auth_login_total")
                .description("Tentativas de login")
                .tag("result",   "success")
                .tag("provider", "local")
                .register(meterRegistry);

        this.loginFailure = Counter.builder("auth_login_total")
                .description("Tentativas de login")
                .tag("result",   "failure")
                .tag("provider", "local")
                .register(meterRegistry);

        this.registerSuccess = Counter.builder("auth_register_total")
                .description("Tentativas de cadastro")
                .tag("result", "success")
                .register(meterRegistry);

        this.registerFailure = Counter.builder("auth_register_total")
                .description("Tentativas de cadastro")
                .tag("result", "failure")
                .register(meterRegistry);
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            registerFailure.increment();
            throw new IllegalArgumentException("E-mail já cadastrado");
        }

        String hash = passwordEncoder.encode(request.password());
        User user = new User(request.firstName(), request.lastName(), request.email(), hash);
        userRepository.save(user);

        registerSuccess.increment();
        return issueTokens(user);
    }

    @Transactional
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    loginFailure.increment();
                    return new BadCredentialsException("Credenciais inválidas");
                });

        if (!"local".equals(user.getProvider())) {
            loginFailure.increment();
            throw new BadCredentialsException(
                    "Esta conta usa login via " + user.getProvider() + ". Use o botão de login social."
            );
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginFailure.increment();
            throw new BadCredentialsException("Credenciais inválidas");
        }

        user = userRepository.findByIdWithCouple(user.getId()).orElseThrow();

        loginSuccess.increment();
        return issueTokens(user);
    }

    private AuthDtos.TokenResponse issueTokens(User user) {
        String accessToken   = jwtService.generateAccessToken(user);
        RefreshToken refresh = refreshTokenService.create(user);

        return new AuthDtos.TokenResponse(
                accessToken,
                refresh.getToken(),
                toUserInfo(user)
        );
    }

    private AuthDtos.UserInfo toUserInfo(User user) {
        return new AuthDtos.UserInfo(
                user.getId().toString(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getCouple() != null ? user.getCouple().getId().toString() : null
        );
    }
}