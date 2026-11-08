package br.dev.duofinance.recurring.service;

import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import br.dev.duofinance.subscription.service.EntitlementService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily generation of due recurring occurrences. Runs without a request context: each rule gets its own transaction,
 * accounts without subscription access are skipped without touching or locking their rules, and a failing rule is
 * logged and counted without stopping the others.
 */
@Component
public class RecurringGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(RecurringGenerationJob.class);

    public record JobResult(int rules, int generated, int skippedNoAccess, int failed) {}

    private final RecurringRuleRepository rules;
    private final RecurringGenerator generator;
    private final EntitlementService entitlements;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RecurringGenerationJob(
            RecurringRuleRepository rules,
            RecurringGenerator generator,
            EntitlementService entitlements,
            Clock clock,
            PlatformTransactionManager txManager) {
        this.rules = rules;
        this.generator = generator;
        this.entitlements = entitlements;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "UTC")
    public JobResult run() {
        LocalDate today = LocalDate.now(clock);
        List<UUID> dueIds = rules.findDueRuleIds(today);
        Map<UUID, Boolean> access = new HashMap<>();
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID ruleId : dueIds) {
            try {
                UUID accountId = rules.findAccountId(ruleId).orElse(null);
                if (accountId == null) {
                    continue; // deleted since the listing
                }
                if (!access.computeIfAbsent(accountId, entitlements::hasAccess)) {
                    skipped++;
                    continue;
                }
                // generateDue locks the rule and generates nothing if it was deactivated or is no longer due.
                Integer inserted = tx.execute(status -> generator.generateDue(ruleId, today));
                generated += inserted == null ? 0 : inserted;
            } catch (RuntimeException e) {
                failed++;
                log.error("Recurring generation failed for rule {}", ruleId, e);
            }
        }
        JobResult result = new JobResult(dueIds.size(), generated, skipped, failed);
        log.info("Recurring generation finished: {}", result);
        return result;
    }
}
