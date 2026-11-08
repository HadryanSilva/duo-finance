package br.dev.duofinance.recurring.service;

import br.dev.duofinance.recurring.model.RecurringRule;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes a rule's due occurrences as transactions. The rule row is locked for the caller's transaction, and the
 * unique {@code (recurring_rule_id, date)} index with {@code ON CONFLICT DO NOTHING} keeps concurrent or repeated runs
 * from duplicating an occurrence.
 */
@Component
public class RecurringGenerator {

    /** Copies the rule's current values; the rule must be flushed before this runs. */
    private static final String INSERT_OCCURRENCE = """
            INSERT INTO transactions (id, account_id, kind, amount_cents, date, description, category_id,
                                      bank_account_id, destination_bank_account_id, created_by, recurring_rule_id,
                                      created_at, updated_at)
            SELECT :id, r.account_id, r.kind, r.amount_cents, :date, r.description, r.category_id,
                   r.bank_account_id, r.destination_bank_account_id, r.created_by, r.id, :now, :now
            FROM recurring_rules r
            WHERE r.id = :ruleId
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
        RecurringRule rule = rules.findByIdForUpdate(ruleId).orElse(null);
        if (rule == null || !rule.isActive()) {
            return 0;
        }
        entityManager.flush();
        Instant now = Instant.now(clock);
        SharedSessionContractImplementor session = entityManager.unwrap(SharedSessionContractImplementor.class);
        int inserted = 0;
        for (LocalDate date : RecurrenceSchedule.between(
                rule.getStartDate(), rule.getFrequency(), rule.getEndDate(), rule.getNextRunDate(), today)) {
            inserted += entityManager
                    .createNativeQuery(INSERT_OCCURRENCE)
                    .setParameter("id", UuidVersion7Strategy.INSTANCE.generateUuid(session))
                    .setParameter("date", date)
                    .setParameter("now", now)
                    .setParameter("ruleId", rule.getId())
                    .executeUpdate();
        }
        rule.setNextRunDate(
                RecurrenceSchedule.firstOnOrAfter(rule.getStartDate(), rule.getFrequency(), today.plusDays(1)));
        return inserted;
    }
}
