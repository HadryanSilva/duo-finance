package br.com.hadryan.duo.finance.notification;

import br.com.hadryan.duo.finance.notification.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    // Últimas 30 notificações do usuário (lidas e não lidas)
    @Query("""
            SELECT n FROM notifications n
            WHERE n.user.id = :userId
            ORDER BY n.createdAt DESC
            LIMIT 30
            """)
    List<Notification> findTop30ByUserId(@Param("userId") UUID userId);

    long countByUserIdAndReadFalse(UUID userId);

    @Modifying
    @Query("UPDATE notifications n SET n.read = true WHERE n.user.id = :userId AND n.read = false")
    void markAllAsReadByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE notifications n SET n.read = true WHERE n.id = :id AND n.user.id = :userId")
    void markAsRead(@Param("id") UUID id, @Param("userId") UUID userId);

    // Evita duplicata de notificação do mesmo tipo no mesmo dia
    @Query("""
            SELECT COUNT(n) > 0 FROM notifications n
            WHERE n.user.id   = :userId
              AND n.type       = :type
              AND n.message    LIKE :messagePrefix
              AND CAST(n.createdAt AS date) = CURRENT_DATE
            """)
    boolean existsTodayByUserAndTypeAndMessage(
            @Param("userId")        UUID userId,
            @Param("type") NotificationType type,
            @Param("messagePrefix") String messagePrefix
    );
}