package br.com.hadryan.duo.finance.metrics;

import br.com.hadryan.duo.finance.couple.CoupleRepository;
import br.com.hadryan.duo.finance.transaction.TransactionRepository;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas de negócio expostas via Micrometer para coleta pelo Prometheus.
 *
 * Métricas geradas:
 *   business_users_total              — total de usuários cadastrados
 *   business_users_with_couple_total  — usuários vinculados a um casal
 *   business_couples_total            — total de contas de casal
 *   business_couples_complete_total   — casais com 2 membros
 *   business_transactions_today_total — transações criadas hoje (income/expense)
 *   business_transactions_total       — total de transações ativas (não deletadas)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry       meterRegistry;
    private final UserRepository      userRepository;
    private final CoupleRepository    coupleRepository;
    private final TransactionRepository transactionRepository;

    // Valores em cache — atualizados pelo @Scheduled para não bater no banco a cada scrape
    private final AtomicLong usersTotal             = new AtomicLong(0);
    private final AtomicLong usersWithCouple        = new AtomicLong(0);
    private final AtomicLong couplesTotal           = new AtomicLong(0);
    private final AtomicLong couplesComplete        = new AtomicLong(0);
    private final AtomicLong transactionsTodayIncome  = new AtomicLong(0);
    private final AtomicLong transactionsTodayExpense = new AtomicLong(0);
    private final AtomicLong transactionsTotal      = new AtomicLong(0);

    @PostConstruct
    public void registerGauges() {
        // ── Usuários ──────────────────────────────────────────────────────────
        Gauge.builder("business_users_total", usersTotal, AtomicLong::get)
                .description("Total de usuarios cadastrados")
                .register(meterRegistry);

        Gauge.builder("business_users_with_couple_total", usersWithCouple, AtomicLong::get)
                .description("Usuarios vinculados a um casal")
                .register(meterRegistry);

        // ── Casais ────────────────────────────────────────────────────────────
        Gauge.builder("business_couples_total", couplesTotal, AtomicLong::get)
                .description("Total de contas de casal criadas")
                .register(meterRegistry);

        Gauge.builder("business_couples_complete_total", couplesComplete, AtomicLong::get)
                .description("Casais com 2 membros vinculados")
                .register(meterRegistry);

        // ── Transações hoje ───────────────────────────────────────────────────
        Gauge.builder("business_transactions_today_total", transactionsTodayIncome, AtomicLong::get)
                .description("Transacoes criadas hoje")
                .tag("type", "INCOME")
                .register(meterRegistry);

        Gauge.builder("business_transactions_today_total", transactionsTodayExpense, AtomicLong::get)
                .description("Transacoes criadas hoje")
                .tag("type", "EXPENSE")
                .register(meterRegistry);

        // ── Total de transações ───────────────────────────────────────────────
        Gauge.builder("business_transactions_total", transactionsTotal, AtomicLong::get)
                .description("Total de transacoes ativas (nao deletadas)")
                .register(meterRegistry);

        // Carrega valores iniciais imediatamente
        refreshMetrics();
    }

    /**
     * Atualiza os valores a cada 2 minutos.
     * Não executa a cada scrape do Prometheus (15s) para evitar N queries ao banco.
     */
    @Scheduled(fixedDelay = 2 * 60 * 1_000)
    public void refreshMetrics() {
        try {
            usersTotal.set(userRepository.count());
            usersWithCouple.set(userRepository.countByHasCouple());
            couplesTotal.set(coupleRepository.count());
            couplesComplete.set(coupleRepository.countComplete());
            transactionsTodayIncome.set(transactionRepository.countTodayByType(TransactionType.INCOME));
            transactionsTodayExpense.set(transactionRepository.countTodayByType(TransactionType.EXPENSE));
            transactionsTotal.set(transactionRepository.countActive());
        } catch (Exception e) {
            log.warn("Erro ao atualizar metricas de negocio: {}", e.getMessage());
        }
    }
}