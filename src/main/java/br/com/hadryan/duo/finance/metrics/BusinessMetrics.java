package br.com.hadryan.duo.finance.config;

import br.com.hadryan.duo.finance.couple.CoupleRepository;
import br.com.hadryan.duo.finance.transaction.TransactionRepository;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas de negócio expostas via Micrometer.
 *
 * Métricas geradas:
 *   business_users              — total de usuários cadastrados
 *   business_users_with_couple  — usuários vinculados a um casal ativo
 *   business_couples            — casais com pelo menos 1 membro (exclui fantasmas)
 *   business_couples_complete   — casais com 2 membros
 *   business_transactions_today — transações registradas hoje por createdAt (por tipo)
 *   business_transactions       — total de transações ativas (não deletadas)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry         meterRegistry;
    private final UserRepository        userRepository;
    private final CoupleRepository      coupleRepository;
    private final TransactionRepository transactionRepository;

    private final AtomicLong usersTotal               = new AtomicLong(0);
    private final AtomicLong usersWithCouple          = new AtomicLong(0);
    private final AtomicLong couplesActive            = new AtomicLong(0);
    private final AtomicLong couplesComplete          = new AtomicLong(0);
    private final AtomicLong transactionsTodayIncome  = new AtomicLong(0);
    private final AtomicLong transactionsTodayExpense = new AtomicLong(0);
    private final AtomicLong transactionsTotal        = new AtomicLong(0);

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("business_users", usersTotal, AtomicLong::get)
                .description("Total de usuarios cadastrados")
                .register(meterRegistry);

        Gauge.builder("business_users_with_couple", usersWithCouple, AtomicLong::get)
                .description("Usuarios vinculados a um casal ativo")
                .register(meterRegistry);

        Gauge.builder("business_couples", couplesActive, AtomicLong::get)
                .description("Casais com pelo menos 1 membro ativo")
                .register(meterRegistry);

        Gauge.builder("business_couples_complete", couplesComplete, AtomicLong::get)
                .description("Casais com 2 membros vinculados")
                .register(meterRegistry);

        // Conta por createdAt (quando o registro foi criado), não por date (data financeira)
        Gauge.builder("business_transactions_today", transactionsTodayIncome, AtomicLong::get)
                .description("Transacoes registradas hoje (por createdAt)")
                .tag("type", "INCOME")
                .register(meterRegistry);

        Gauge.builder("business_transactions_today", transactionsTodayExpense, AtomicLong::get)
                .description("Transacoes registradas hoje (por createdAt)")
                .tag("type", "EXPENSE")
                .register(meterRegistry);

        Gauge.builder("business_transactions", transactionsTotal, AtomicLong::get)
                .description("Total de transacoes ativas (nao deletadas)")
                .register(meterRegistry);

        refreshMetrics();
    }

    @Scheduled(fixedDelay = 2 * 60 * 1_000)
    public void refreshMetrics() {
        try {
            usersTotal.set(userRepository.count());
            usersWithCouple.set(userRepository.countByHasCouple());
            couplesActive.set(coupleRepository.countActive());
            couplesComplete.set(coupleRepository.countComplete());
            transactionsTodayIncome.set(
                    transactionRepository.countCreatedTodayByType(TransactionType.INCOME));
            transactionsTodayExpense.set(
                    transactionRepository.countCreatedTodayByType(TransactionType.EXPENSE));
            transactionsTotal.set(transactionRepository.countActive());
        } catch (Exception e) {
            log.warn("Erro ao atualizar metricas de negocio: {}", e.getMessage());
        }
    }
}