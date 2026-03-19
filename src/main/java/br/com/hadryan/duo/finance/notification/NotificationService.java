package br.com.hadryan.duo.finance.notification;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.notification.dto.NotificationDtos;
import br.com.hadryan.duo.finance.notification.enums.NotificationType;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository         notificationRepository;
    private final NotificationSettingsRepository settingsRepository;
    private final UserRepository                 userRepository;

    // ── Criar notificação ─────────────────────────────────────────────────────

    /**
     * Persiste uma notificação para um usuário específico.
     * Verifica preferências antes de criar.
     * Verifica duplicata no mesmo dia para evitar spam.
     */
    @Transactional
    public void create(User user, Couple couple, NotificationType type,
                       String title, String message) {
        if (!isEnabled(user.getId())) return;

        // Evita duplicata no mesmo dia para o mesmo tipo e categoria
        String prefix = message.length() > 60 ? message.substring(0, 60) + "%" : message + "%";
        if (notificationRepository.existsTodayByUserAndTypeAndMessage(user.getId(), type, prefix)) {
            log.debug("Notificação duplicada ignorada: user={} type={}", user.getId(), type);
            return;
        }

        Notification n = new Notification();
        n.setUser(user);
        n.setCouple(couple);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        notificationRepository.save(n);
    }

    /**
     * Persiste notificação para todos os membros do casal.
     */
    @Transactional
    public void createForCouple(Couple couple, NotificationType type,
                                String title, String message) {
        userRepository.findByCoupleId(couple.getId())
                .forEach(u -> create(u, couple, type, title, message));
    }

    /**
     * Persiste notificação para todos os membros do casal exceto um usuário.
     */
    @Transactional
    public void createForCoupleExcept(Couple couple, UUID excludeUserId,
                                      NotificationType type, String title, String message) {
        userRepository.findByCoupleId(couple.getId()).stream()
                .filter(u -> !u.getId().equals(excludeUserId))
                .forEach(u -> create(u, couple, type, title, message));
    }

    // ── Listar ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NotificationDtos.NotificationListResponse list(User currentUser) {
        List<Notification> notifications =
                notificationRepository.findTop30ByUserId(currentUser.getId());

        long unreadCount = notificationRepository
                .countByUserIdAndReadFalse(currentUser.getId());

        List<NotificationDtos.NotificationResponse> items = notifications.stream()
                .map(n -> new NotificationDtos.NotificationResponse(
                        n.getId(), n.getType(), n.getTitle(),
                        n.getMessage(), n.isRead(), n.getCreatedAt()))
                .toList();

        return new NotificationDtos.NotificationListResponse(items, unreadCount);
    }

    // ── Marcar como lida ──────────────────────────────────────────────────────

    @Transactional
    public void markAsRead(UUID id, User currentUser) {
        notificationRepository.markAsRead(id, currentUser.getId());
    }

    @Transactional
    public void markAllAsRead(User currentUser) {
        notificationRepository.markAllAsReadByUserId(currentUser.getId());
    }

    // ── Preferências ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NotificationDtos.NotificationSettingsResponse getSettings(User currentUser) {
        boolean enabled = settingsRepository.findById(currentUser.getId())
                .map(NotificationSettings::isEnabled)
                .orElse(true); // padrão: notificações ativas
        return new NotificationDtos.NotificationSettingsResponse(enabled);
    }

    @Transactional
    public NotificationDtos.NotificationSettingsResponse toggleSettings(User currentUser) {
        NotificationSettings settings = settingsRepository
                .findById(currentUser.getId())
                .orElseGet(() -> new NotificationSettings(currentUser.getId()));

        settings.setEnabled(!settings.isEnabled());
        settingsRepository.save(settings);
        return new NotificationDtos.NotificationSettingsResponse(settings.isEnabled());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private boolean isEnabled(UUID userId) {
        return settingsRepository.findById(userId)
                .map(NotificationSettings::isEnabled)
                .orElse(true);
    }
}