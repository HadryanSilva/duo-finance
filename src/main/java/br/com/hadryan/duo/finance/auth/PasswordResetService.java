package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.InvalidTokenException;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${spring.mail.username}")
    private String mailFrom;

    // ── Solicitar redefinição ─────────────────────────────────────────────────

    /**
     * Sempre retorna sem erro — mesmo se o e-mail não existir ou for conta Google.
     * Isso evita enumeração de e-mails cadastrados.
     */
    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            // Bloqueia contas OAuth2 — não têm senha local
            if (!"local".equals(user.getProvider())) return;

            // Invalida tokens anteriores
            tokenRepository.invalidateAllByUserId(user.getId());

            // Gera novo token
            String raw     = UUID.randomUUID().toString().replace("-", "");
            LocalDateTime expires = LocalDateTime.now().plusHours(1);
            tokenRepository.save(new PasswordResetToken(user, raw, expires));

            sendResetEmail(user, raw);
        });
    }

    // ── Redefinir senha ───────────────────────────────────────────────────────

    @Transactional
    public void resetPassword(String tokenValue, String newPassword) {
        PasswordResetToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new InvalidTokenException("Link inválido ou já utilizado."));

        if (!token.isValid()) {
            throw new InvalidTokenException("Este link expirou. Solicite um novo.");
        }

        User user = token.getUser();

        if (!"local".equals(user.getProvider())) {
            throw new BusinessException("Esta conta usa login via Google e não possui senha local.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);
    }

    // ── E-mail ────────────────────────────────────────────────────────────────

    private void sendResetEmail(User user, String token) {
        String resetUrl = frontendUrl + "/reset-password?token=" + token;

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(mailFrom);
        mail.setTo(user.getEmail());
        mail.setSubject("🔑 Redefinição de senha — DuoFinance");
        mail.setText("""
                Olá, %s!

                Recebemos uma solicitação para redefinir a senha da sua conta no DuoFinance.

                Clique no link abaixo para criar uma nova senha:
                %s

                O link expira em 1 hora. Se você não solicitou a redefinição, ignore este e-mail.

                — Equipe DuoFinance
                """.formatted(user.getFirstName(), resetUrl));

        mailSender.send(mail);
    }
}
