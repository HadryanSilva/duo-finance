package br.com.hadryan.duo.finance.notification.dto;

import br.com.hadryan.duo.finance.notification.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class NotificationDtos {

    public record NotificationResponse(
            UUID id,
            NotificationType type,
            String title,
            String message,
            boolean read,
            LocalDateTime createdAt
    ) {}

    public record NotificationListResponse(
            List<NotificationResponse> notifications,
            long unreadCount
    ) {}

    public record NotificationSettingsResponse(
            boolean enabled
    ) {}
}