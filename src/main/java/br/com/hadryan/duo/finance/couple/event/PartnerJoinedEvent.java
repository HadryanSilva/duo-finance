package br.com.hadryan.duo.finance.couple.event;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.user.User;

/**
 * Publicado pelo CoupleService quando um parceiro aceita o convite e se vincula ao casal.
 * Consumido pelo NotificationListener para persistir notificação in-app.
 */
public record PartnerJoinedEvent(
        User joiningUser,   // usuário que acabou de aceitar o convite
        User existingUser,  // usuário que já estava no casal
        Couple couple
) {}