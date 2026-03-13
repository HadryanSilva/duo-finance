package br.com.hadryan.duo.finance.transaction;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
@Component
public class RecurrenceScheduler {

    private final TransactionRepository repository;

    // ── Cron diário — roda todo dia às 01:00 ─────────────────────────────────

    @Scheduled(cron = "0 0 1 * * *")
    public void dailyGeneration() {
        log.info("Scheduler diário de recorrência iniciado");
        processUntil(LocalDate.now());
    }

    // ── Backfill — roda uma vez ao subir a aplicação ──────────────────────────

    /**
     * Ao iniciar, verifica se há dias perdidos para cada pai recorrente e os preenche.
     * Cobre o caso de downtime do servidor (restart, manutenção, etc.).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        log.info("Backfill de recorrências iniciado");
        processUntil(LocalDate.now());
        log.info("Backfill de recorrências concluído");
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    /**
     * Para cada pai recorrente ativo, calcula todas as datas devidas desde a
     * última ocorrência registrada (ou a data de criação do pai) até {@code until},
     * e cria os filhos faltantes com idempotência.
     */
    @Transactional
    public void processUntil(LocalDate until) {
        List<Transaction> parents = repository.findActiveRecurringParents(until);
        log.info("Recorrências ativas: {}", parents.size());

        int created = 0;
        int skipped = 0;

        for (Transaction parent : parents) {
            // Ponto de partida: dia seguinte ao último filho gerado, ou a própria data do pai
            LocalDate lastGenerated = repository.findLastChildDate(parent.getId())
                    .orElse(parent.getDate());

            List<LocalDate> dueDates = resolveDueDates(parent, lastGenerated, until);

            for (LocalDate date : dueDates) {
                if (repository.existsChildForDate(parent.getId(), date)) {
                    skipped++;
                    continue;
                }
                repository.save(buildChild(parent, date));
                created++;
                log.debug("Filho criado: parent={} date={}", parent.getId(), date);
            }
        }

        log.info("Recorrências processadas — criados: {}, já existiam: {}", created, skipped);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Retorna todas as datas devidas para um pai no intervalo (lastGenerated, until].
     * O limite é exclusivo na esquerda — não regera a última data já existente.
     */
    private List<LocalDate> resolveDueDates(Transaction parent, LocalDate lastGenerated, LocalDate until) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = nextOccurrenceAfter(parent, lastGenerated);

        while (cursor != null && !cursor.isAfter(until)) {
            // Respeita recurrenceEndDate do pai
            if (parent.getRecurrenceEndDate() != null && cursor.isAfter(parent.getRecurrenceEndDate())) break;
            dates.add(cursor);
            cursor = nextOccurrenceAfter(parent, cursor);
        }

        return dates;
    }

    /**
     * Retorna a próxima ocorrência estritamente após {@code from}, de acordo com a regra do pai.
     */
    private LocalDate nextOccurrenceAfter(Transaction parent, LocalDate from) {
        return switch (parent.getRecurrenceRule()) {
            case DAILY   -> from.plusDays(1);
            case WEEKLY  -> from.plusWeeks(1);
            case MONTHLY -> {
                // Avança um mês mantendo o dia original; trata meses mais curtos com .withDayOfMonth
                LocalDate candidate = from.plusMonths(1);
                int targetDay = parent.getDate().getDayOfMonth();
                int maxDay    = candidate.lengthOfMonth();
                yield candidate.withDayOfMonth(Math.min(targetDay, maxDay));
            }
            case YEARLY -> from.plusYears(1);
        };
    }

    private Transaction buildChild(Transaction parent, LocalDate date) {
        Transaction child = new Transaction();
        child.setCouple(parent.getCouple());
        child.setUser(parent.getUser());
        child.setCategory(parent.getCategory());
        child.setType(parent.getType());
        child.setAmount(parent.getAmount());
        child.setDescription(parent.getDescription());
        child.setDate(date);
        child.setRecurring(false);           // filho não gera novos filhos
        child.setParentTransaction(parent);
        return child;
    }
}