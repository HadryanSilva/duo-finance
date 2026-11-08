package br.dev.duofinance.bankaccount.repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Computes balances in a single aggregate; there is no Transaction entity to join through. */
@Repository
public class BankAccountBalanceQuery {

    private static final String SQL = """
            SELECT b.id,
                   b.initial_balance_cents + COALESCE(SUM(CASE
                       WHEN t.kind = 'INCOME' AND t.bank_account_id = b.id THEN t.amount_cents
                       WHEN t.kind = 'EXPENSE' AND t.bank_account_id = b.id THEN -t.amount_cents
                       WHEN t.kind = 'TRANSFER' AND t.destination_bank_account_id = b.id THEN t.amount_cents
                       WHEN t.kind = 'TRANSFER' AND t.bank_account_id = b.id THEN -t.amount_cents
                       ELSE 0 END), 0)::bigint AS balance_cents
            FROM bank_accounts b
            LEFT JOIN transactions t
                   ON t.account_id = b.account_id
                  AND t.date <= :asOf
                  AND (t.bank_account_id = b.id OR t.destination_bank_account_id = b.id)
            WHERE b.account_id = :accountId
            GROUP BY b.id, b.initial_balance_cents
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BankAccountBalanceQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Balance of every bank account of the account as of the given date (inclusive). */
    public Map<UUID, Long> balances(UUID accountId, LocalDate asOf) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(
                SQL,
                new MapSqlParameterSource().addValue("accountId", accountId).addValue("asOf", asOf),
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getLong("balance_cents"));
                });
        return result;
    }
}
