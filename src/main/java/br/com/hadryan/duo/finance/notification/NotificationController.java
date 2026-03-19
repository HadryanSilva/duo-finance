package br.com.hadryan.duo.finance.notification;

import br.com.hadryan.duo.finance.notification.dto.NotificationDtos;
import br.com.hadryan.duo.finance.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** GET /api/notifications — lista as últimas 30 + contagem de não lidas */
    @GetMapping
    public ResponseEntity<NotificationDtos.NotificationListResponse> list(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(notificationService.list(currentUser));
    }

    /** PATCH /api/notifications/{id}/read — marca uma notificação como lida */
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        notificationService.markAsRead(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    /** PATCH /api/notifications/read-all — marca todas como lidas */
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(
            @AuthenticationPrincipal User currentUser
    ) {
        notificationService.markAllAsRead(currentUser);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/notifications/settings — retorna preferências do usuário */
    @GetMapping("/settings")
    public ResponseEntity<NotificationDtos.NotificationSettingsResponse> getSettings(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(notificationService.getSettings(currentUser));
    }

    /** PATCH /api/notifications/settings/toggle — ativa ou desativa notificações */
    @PatchMapping("/settings/toggle")
    public ResponseEntity<NotificationDtos.NotificationSettingsResponse> toggleSettings(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(notificationService.toggleSettings(currentUser));
    }
}