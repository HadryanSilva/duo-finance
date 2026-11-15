package br.dev.duofinance.recurring.service;

import br.dev.duofinance.recurring.model.RecurringRule;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes a rule's due occurrences as transactions. The rule row is locked for the caller's transaction, and the
 * unique {@code (recurring_rule_id, date)} index with {@code ON CONFLICT DO NOTHING} keeps concurrent or repeated runs
 * from duplicating an occurrence. Lock order: the account ({@code KEY SHARE}) before the rule ({@code FOR UPDATE}),
 * the same order account deletion uses, so the two cannot deadlock.
 */
@Component
public class RecurringGenerator {

    /**
     * Copies the rule's current values into one transaction per (id, date) pair, in a single statement however many
     * occurrences are due; the rule must be flushed before this runs. Parameters: now, now, ids, dates, ruleId.
     */
    private static final String INSERT_OCCURRENCES = """
            INSERT INTO transactions (id, account_id, kind, amount_cents, date, description, category_id,
                                      bank_account_id, destination_bank_account_id, created_by, recurring_rule_id,
                                      created_at, updated_at)
            SELECT o.id, r.account_id, r.kind, r.amount_cents, o.date, r.description, r.category_id,
                   r.bank_account_id, r.destination_bank_account_id, r.created_by, r.id, ?, ?
            FROM recurring_rules r
            CROSS JOIN unnest(?::uuid[], ?::date[]) AS o(id, date)
            WHERE r.id = ?
            ON CONFLICT (recurring_rule_id, date) WHERE recurring_rule_id IS NOT NULL DO NOTHING
            """;

    private final RecurringRuleRepository rules;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public RecurringGenerator(RecurringRuleRepository rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Inserts every occurrence in {@code [nextRunDate, min(today, endDate)]} of an active rule, then moves
     * {@code nextRunDate} to the first occurrence after {@code today}. Returns how many transactions were inserted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int generateDue(UUID ruleId, LocalDate today) {
        if (rules.lockAccountOfRule(ruleId).isEmpty()) {
            return 0;
        }
        RecurringRule rule = rules.findByIdForUpdate(ruleId).orElse(null);
        if (rule == null || !rule.isActive()) {
            return 0;
        }
        entityManager.flush();
        List<LocalDate> dates = RecurrenceSchedule.between(
                rule.getStartDate(), rule.getFrequency(), rule.getEndDate(), rule.getNextRunDate(), today);
        int inserted = dates.isEmpty() ? 0 : insertOccurrences(rule.getId(), dates, Instant.now(clock));
        rule.setNextRunDate(
                RecurrenceSchedule.firstOnOrAfter(rule.getStartDate(), rule.getFrequency(), today.plusDays(1)));
        return inserted;
    }

    private int insertOccurrences(UUID ruleId, List<LocalDate> dates, Instant now) {
        SharedSessionContractImplementor session = entityManager.unwrap(SharedSessionContractImplementor.class);
        Object[] ids = new Object[dates.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UuidVersion7Strategy.INSTANCE.generateUuid(session);
        }
        Object[] days = dates.stream().map(java.sql.Date::valueOf).toArray();
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        return session.doReturningWork(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_OCCURRENCES)) {
                insert.setObject(1, timestamp);
                insert.setObject(2, timestamp);
                insert.setArray(3, connection.createArrayOf("uuid", ids));
                insert.setArray(4, connection.createArrayOf("date", days));
                insert.setObject(5, ruleId);
                return insert.executeUpdate();
            }
        });
    }
}
