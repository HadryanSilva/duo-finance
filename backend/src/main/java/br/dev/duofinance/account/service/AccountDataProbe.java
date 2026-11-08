package br.dev.duofinance.account.service;

import java.util.UUID;

/**
 * Reports whether an account holds data its user created (as opposed to seeded defaults). Accepting an invitation
 * discards the invitee's solo account, so any probe answering {@code true} requires explicit confirmation.
 */
public interface AccountDataProbe {

    boolean hasUserData(UUID accountId);
}
