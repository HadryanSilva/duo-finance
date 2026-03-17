package br.com.hadryan.duo.finance.couple;

import br.com.hadryan.duo.finance.couple.dto.CoupleDtos;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.shared.exception.ResourceNotFoundException;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class CoupleService {

    private final CoupleRepository  coupleRepository;
    private final UserRepository    userRepository;
    private final JavaMailSender    mailSender;
    private final TemplateEngine    templateEngine;
    private final long              inviteExpirationHours;
    private final String            frontendUrl;
    private final String            mailFrom;

    private static final DateTimeFormatter EXPIRES_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR"));

    public CoupleService(
            CoupleRepository coupleRepository,
            UserRepository userRepository,
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${app.invite-expiration-hours}") long inviteExpirationHours,
            @Value("${app.frontend-url}")            String frontendUrl,
            @Value("${spring.mail.username}")        String mailFrom
    ) {
        this.coupleRepository      = coupleRepository;
        this.userRepository        = userRepository;
        this.mailSender            = mailSender;
        this.templateEngine        = templateEngine;
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

        Couple couple = new Couple();
        couple.setName(request.name());
        coupleRepository.save(couple);

        currentUser.setCouple(couple);
        userRepository.save(currentUser);

        return toResponse(couple, List.of(currentUser));
    }

    // ── Buscar casal do usuário ───────────────────────────────────────────────

    @Transactional
    public CoupleDtos.CoupleResponse findMine(User currentUser) {
        Couple couple      = requireCouple(currentUser);
        List<User> members = userRepository.findByCoupleId(couple.getId());
        return toResponse(couple, members);
    }

    // ── Atualizar nome do casal ───────────────────────────────────────────────

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

        List<User> members = userRepository.findByCoupleId(couple.getId());
        if (members.size() >= 2) {
            throw new BusinessException("Esta conta já possui dois parceiros.");
        }

        boolean alreadyMember = members.stream()
                .anyMatch(u -> u.getEmail().equalsIgnoreCase(request.partnerEmail()));
        if (alreadyMember) {
            throw new BusinessException("Este e-mail já é membro desta conta.");
        }

        String token      = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.now().plusHours(inviteExpirationHours);

        couple.setInviteToken(token);
        couple.setInviteExpiresAt(expires);
        coupleRepository.save(couple);

        sendInviteEmail(request.partnerEmail(), currentUser, couple, token, expires);

        return new CoupleDtos.InviteResponse(
                "Convite enviado para " + request.partnerEmail(),
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

        currentUser.setCouple(couple);
        userRepository.save(currentUser);

        couple.setInviteToken(null);
        couple.setInviteExpiresAt(null);
        coupleRepository.save(couple);

        List<User> members = userRepository.findByCoupleId(couple.getId());
        return new CoupleDtos.JoinCoupleResponse(
                "Você foi vinculado à conta com sucesso!",
                toResponse(couple, members)
        );
    }

    // ── RF34: Desvincular membro do casal ─────────────────────────────────────

    @Transactional
    public void removeMember(UUID targetUserId, User currentUser) {
        Couple couple = requireCouple(currentUser);

        List<User> members = userRepository.findByCoupleId(couple.getId());

        // Validação: só faz sentido desvincular quando há 2 membros
        if (members.size() < 2) {
            throw new BusinessException("Não é possível desvincular — o casal tem apenas um membro.");
        }

        // Validação: o alvo deve ser membro do mesmo casal
        User target = members.stream()
                .filter(u -> u.getId().equals(targetUserId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Usuário não encontrado nesta conta de casal."));

        // Desvincula: seta couple = null no usuário removido
        target.setCouple(null);
        userRepository.save(target);

        log.info("Membro {} desvinculado do casal {} por {}",
                targetUserId, couple.getId(), currentUser.getId());
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
        String inviteUrl  = frontendUrl + "/invite/" + token;
        String senderName = sender.getFirstName() + " " + sender.getLastName();

        Context ctx = new Context(Locale.forLanguageTag("pt-BR"));
        ctx.setVariable("senderName",      senderName);
        ctx.setVariable("senderFirstName", sender.getFirstName());
        ctx.setVariable("senderAvatar",    sender.getAvatarUrl());
        ctx.setVariable("coupleName",      couple.getName());
        ctx.setVariable("inviteUrl",       inviteUrl);
        ctx.setVariable("expiresAt",       expires.format(EXPIRES_FMT));

        String html = templateEngine.process("emails/invite-partner", ctx);

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(sender.getFirstName() + " te convidou para o DuoFinance 💑");
            helper.setText(html, true);
            mailSender.send(mime);
        } catch (Exception e) {
            log.error("Falha ao enviar e-mail de convite para {}: {}", to, e.getMessage(), e);
            throw new BusinessException("Não foi possível enviar o e-mail de convite. Tente novamente.");
        }
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