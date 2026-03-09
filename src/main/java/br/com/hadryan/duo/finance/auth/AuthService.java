package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.auth.jwt.JwtService;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("E-mail já cadastrado");
        }

        String hash = passwordEncoder.encode(request.password());
        User user = new User(request.firstName(), request.lastName(), request.email(), hash);
        userRepository.save(user);

        return issueTokens(user);
    }

    @Transactional
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas"));

        if (!"local".equals(user.getProvider())) {
            throw new BadCredentialsException(
                    "Esta conta usa login via " + user.getProvider() + ". Use o botão de login social."
            );
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Credenciais inválidas");
        }

        // Recarrega com couple para emitir JWT correto
        user = userRepository.findByIdWithCouple(user.getId()).orElseThrow();

        return issueTokens(user);
    }

    private AuthDtos.TokenResponse issueTokens(User user) {
        String accessToken  = jwtService.generateAccessToken(user);
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
