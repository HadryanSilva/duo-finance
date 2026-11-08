package br.dev.duofinance.recurring.service;

import br.dev.duofinance.recurring.dto.CreateRecurringRuleRequest;
import br.dev.duofinance.recurring.dto.RecurringRuleResponse;
import br.dev.duofinance.recurring.dto.RecurringRuleResponseAssembler;
import br.dev.duofinance.recurring.dto.UpdateRecurringRuleRequest;
import br.dev.duofinance.recurring.model.RecurringRule;
import br.dev.duofinance.recurring.repository.RecurringRuleRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.finance.FinanceValidation;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator.ReferenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringRuleService {

    private final RecurringRuleRepository rules;
    private final TransactionReferenceValidator validator;
    private final RecurringGenerator generator;
    private final RecurringRuleResponseAssembler assembler;
    private final Clock clock;

    public RecurringRuleService(
            RecurringRuleRepository rules,
            TransactionReferenceValidator validator,
            RecurringGenerator generator,
            RecurringRuleResponseAssembler assembler,
            Clock clock) {
        this.rules = rules;
        this.validator = validator;
        this.generator = generator;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RecurringRuleResponse> list(UUID accountId) {
        return assembler.toResponses(accountId, rules.list(accountId));
    }

    @Transactional(readOnly = true)
    public RecurringRuleResponse get(UUID accountId, UUID id) {
        return respond(accountId, requireOwned(accountId, id));
    }

    /** Occurrences dated up to today are generated in the same transaction. */
    @Transactional
    public RecurringRuleResponse create(UUID accountId, UUID userId, CreateRecurringRuleRequest request) {
        LocalDate start = request.startDate();
        if (start.isBefore(FinanceValidation.MIN_DATE) || start.isAfter(FinanceValidation.MAX_DATE)) {
            throw ApiException.invalidField("startDate", "OutOfRange");
        }
        validateEndDate(start, request.endDate());
        validator.validate(
                accountId,
                request.kind(),
                request.categoryId(),
                request.bankAccountId(),
                request.destinationBankAccountId(),
                ReferenceSnapshot.none());
        Instant now = Instant.now(clock);
        RecurringRule rule = new RecurringRule(accountId, userId, request.kind(), request.frequency(), start, now);
        rule.assign(
                request.amountCents(),
                request.description(),
                request.categoryId(),
                request.bankAccountId(),
                request.destinationBankAccountId(),
                request.endDate(),
                now);
        rules.saveAndFlush(rule);
        generator.generateDue(rule.getId(), today());
        return respond(accountId, rule);
    }

    /** Only future occurrences change; transactions already generated keep their values. */
    @Transactional
    public RecurringRuleResponse update(UUID accountId, UUID id, UpdateRecurringRuleRequest request) {
        RecurringRule rule = requireOwned(accountId, id);
        validateEndDate(rule.getStartDate(), request.endDate());
        validator.validate(
                accountId,
                rule.getKind(),
                request.categoryId(),
                request.bankAccountId(),
                request.destinationBankAccountId(),
                new ReferenceSnapshot(
                        rule.getCategoryId(), rule.getBankAccountId(), rule.getDestinationBankAccountId()));
        rule.assign(
                request.amountCents(),
                request.description(),
                request.categoryId(),
                request.bankAccountId(),
                request.destinationBankAccountId(),
                request.endDate(),
                Instant.now(clock));
        return respond(accountId, rules.saveAndFlush(rule));
    }

    /** Resumes from today: occurrences missed while inactive are not recovered. Already active is a no-op. */
    @Transactional
    public RecurringRuleResponse activate(UUID accountId, UUID id) {
        RecurringRule rule = requireOwned(accountId, id);
        if (!rule.isActive()) {
            LocalDate today = today();
            rule.activate(
                    RecurrenceSchedule.firstOnOrAfter(rule.getStartDate(), rule.getFrequency(), today),
                    Instant.now(clock));
            rules.saveAndFlush(rule);
            generator.generateDue(rule.getId(), today);
        }
        return respond(accountId, rule);
    }

    @Transactional
    public RecurringRuleResponse deactivate(UUID accountId, UUID id) {
        RecurringRule rule = requireOwned(accountId, id);
        if (rule.isActive()) {
            rule.deactivate(Instant.now(clock));
        }
        return respond(accountId, rules.saveAndFlush(rule));
    }

    /** Generated transactions stay, detached from the rule by the foreign key's ON DELETE SET NULL. */
    @Transactional
    public void delete(UUID accountId, UUID id) {
        rules.delete(requireOwned(accountId, id));
        rules.flush();
    }

    private RecurringRule requireOwned(UUID accountId, UUID id) {
        return rules.findByIdAndAccountId(id, accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static void validateEndDate(LocalDate start, LocalDate end) {
        if (end != null && (end.isBefore(start) || end.isAfter(FinanceValidation.MAX_DATE))) {
            throw ApiException.invalidField("endDate", "OutOfRange");
        }
    }

    private RecurringRuleResponse respond(UUID accountId, RecurringRule rule) {
        return assembler.toResponses(accountId, List.of(rule)).get(0);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
