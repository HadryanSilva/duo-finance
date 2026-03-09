package br.com.hadryan.duo.finance.couple;

import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CoupleService {
    private final CoupleRepository coupleRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final long inviteExpirationHours;
    private final String frontendUrl;
    private final String mailFrom;

    public CoupleService(
            CoupleRepository coupleRepository,
            UserRepository userRepository,
            JavaMailSender mailSender,
            @Value("${app.invite-expiration-hours}") long inviteExpirationHours,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${spring.mail.username}") String mailFrom
    ) {
        this.coupleRepository      = coupleRepository;
        this.userRepository        = userRepository;
        this.mailSender            = mailSender;
        this.inviteExpirationHours = inviteExpirationHours;
        this.frontendUrl           = frontendUrl;
        this.mailFrom              = mailFrom;
    }

    // ── Criar conta do casal ──────────────────────────────────────────────────

    @Transactional
    public CoupleDtos.CoupleResponse create(CoupleDtos.CreateCoupleRequest request, User currentUser) {
        if (currentUser.getCouple() != null) {
            throw new BusinessException("Você já pertence a uma conta de casal.");
        }

        Couple couple = coupleRepository.save(new Couple(request.name()));

        currentUser.setCouple(couple);
        userRepository.save(currentUser);

        return toResponse(couple, List.of(currentUser));
    }

    // ── Consultar conta do casal ──────────────────────────────────────────────

    @Transactional
    public CoupleDtos.CoupleResponse findMine(User currentUser) {
        Couple couple = requireCouple(currentUser);
        List<User> members = userRepository.findByCoupleId(couple.getId());
        return toResponse(couple, members);
    }

    // ── Atualizar nome da conta ───────────────────────────────────────────────

    @Transactional
    public CoupleDtos.CoupleResponse update(CoupleDtos.UpdateCoupleRequest request, User currentUser) {
        Couple couple = requireCouple(currentUser);
        couple.setName(request.name());
        coupleRepository.save(couple);

        List<User> members = userRepository.findByCoupleId(couple.getId());
        return toResponse(couple, members);
    }

    // ── Enviar convite ────────────────────────────────────────────────────────

    @Transactional
    public CoupleDtos.InviteResponse invite(CoupleDtos.InvitePartnerRequest request, User currentUser) {
        Couple couple = requireCouple(currentUser);

        // Impede mais de 2 membros
        long memberCount = userRepository.countByCoupleId(couple.getId());
        if (memberCount >= 2) {
            throw new BusinessException("A conta já possui dois parceiros vinculados.");
        }

        // Impede convidar a si mesmo
        if (currentUser.getEmail().equalsIgnoreCase(request.partnerEmail())) {
            throw new BusinessException("Você não pode convidar a si mesmo.");
        }

        // Impede convidar alguém que já tem casal
        userRepository.findByEmail(request.partnerEmail()).ifPresent(user -> {
            if (user.getCouple() != null) {
                throw new BusinessException("Este usuário já pertence a uma conta de casal.");
            }
        });

        // Gera token e salva
        String token          = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expires = LocalDateTime.now().plusHours(inviteExpirationHours);

        couple.setInviteToken(token);
        couple.setInviteExpiresAt(expires);
        coupleRepository.save(couple);

        // Envia e-mail
        sendInviteEmail(request.partnerEmail(), currentUser, couple, token, expires);

        return new CoupleDtos.InviteResponse(
                "Convite enviado com sucesso para " + request.partnerEmail(),
                request.partnerEmail(),
                expires
        );
    }

    // ── Aceitar convite ───────────────────────────────────────────────────────

    @Transactional
    public CoupleDtos.JoinCoupleResponse join(String token, User currentUser) {
        if (currentUser.getCouple() != null) {
            throw new BusinessException("Você já pertence a uma conta de casal.");
        }

        Couple couple = coupleRepository.findByInviteToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Convite não encontrado ou já utilizado."));

        if (couple.getInviteExpiresAt() == null || LocalDateTime.now().isAfter(couple.getInviteExpiresAt())) {
            throw new BusinessException("Este convite expirou. Peça ao seu parceiro que envie um novo.");
        }

        // Vincula o parceiro e invalida o token
        currentUser.setCouple(couple);
        userRepository.save(currentUser);

        couple.setInviteToken(null);
        couple.setInviteExpiresAt(null);
        coupleRepository.save(couple);

        List<User> members = userRepository.findByCoupleId(couple.getId());
        return new CoupleDtos.JoinCoupleResponse("Você foi vinculado à conta com sucesso!", toResponse(couple, members));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Couple requireCouple(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return coupleRepository.findById(user.getCouple().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Conta do casal não encontrada."));
    }

    private void sendInviteEmail(String to, User sender, Couple couple,
                                 String token, LocalDateTime expires) {
        String inviteUrl = frontendUrl + "/invite/" + token;

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(mailFrom);
        mail.setTo(to);
        mail.setSubject("💑 " + sender.getFirstName() + " te convidou para o DuoFinance!");
        mail.setText("""
                Olá!
                
                %s %s te convidou para gerenciar as finanças juntos no DuoFinance.
                
                Acesse o link abaixo para aceitar o convite:
                %s
                
                O convite expira em: %s
                
                Se você não esperava este convite, pode ignorar este e-mail.
                """.formatted(
                sender.getFirstName(),
                sender.getLastName(),
                inviteUrl,
                expires.toString().replace("T", " às ")
        ));

        mailSender.send(mail);
    }

    private CoupleDtos.CoupleResponse toResponse(Couple couple, List<User> members) {
        List<CoupleDtos.PartnerResponse> partnerResponses = members.stream()
                .map(u -> new CoupleDtos.PartnerResponse(
                        u.getId().toString(),
                        u.getFirstName(),
                        u.getLastName(),
                        u.getEmail(),
                        u.getAvatarUrl()
                ))
                .toList();

        return new CoupleDtos.CoupleResponse(
                couple.getId().toString(),
                couple.getName(),
                partnerResponses,
                members.size() < 2,
                couple.getCreatedAt()
        );
    }
}