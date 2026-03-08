package br.com.hadryan.duo.finance.transaction;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
@Component
public class RecurrenceScheduler {
    
    private final TransactionRepository repository;

    /**
     * Roda todo dia às 01:00 — gera os lançamentos filhos para cada pai recorrente.
     * Usa idempotência: só cria se ainda não existe filho para a data de hoje.
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void generateRecurringTransactions() {
        LocalDate today = LocalDate.now();
        List<Transaction> parents = repository.findActiveRecurringParents(today);

        log.info("Scheduler de recorrência: {} transações ativas encontradas", parents.size());

        parents.forEach(parent -> {
            LocalDate nextDate = resolveNextDate(parent, today);

            if (nextDate == null) return;

            // Idempotência — não duplica se já gerou hoje
            if (repository.existsChildForDate(parent.getId(), nextDate)) {
                log.debug("Filho já existe para parent={} date={}", parent.getId(), nextDate);
                return;
            }

            Transaction child = new Transaction();
            child.setCouple(parent.getCouple());
            child.setUser(parent.getUser());
            child.setCategory(parent.getCategory());
            child.setType(parent.getType());
            child.setAmount(parent.getAmount());
            child.setDescription(parent.getDescription());
            child.setDate(nextDate);
            child.setRecurring(false);          // filho não gera novos filhos
            child.setParentTransaction(parent);

            repository.save(child);
            log.debug("Filho criado: parent={} date={}", parent.getId(), nextDate);
        });
    }

    private LocalDate resolveNextDate(Transaction parent, LocalDate today) {
        return switch (parent.getRecurrenceRule()) {
            case DAILY   -> today;
            case WEEKLY  -> today.getDayOfWeek() == parent.getDate().getDayOfWeek() ? today : null;
            case MONTHLY -> parent.getDate().getDayOfMonth() == today.getDayOfMonth() ? today : null;
            case YEARLY  -> parent.getDate().getDayOfYear() == today.getDayOfYear() ? today : null;
        };
    }
}



