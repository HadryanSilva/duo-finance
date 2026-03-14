package br.com.hadryan.duo.finance;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Container PostgreSQL compartilhado entre todos os testes.
 * Usando o padrão Singleton do Testcontainers para evitar que cada
 * classe de teste suba um container separado.
 */
public class SharedPostgresContainer {

    public static final PostgreSQLContainer<?> INSTANCE;

    static {
        INSTANCE = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("duofinance_test")
                .withUsername("test")
                .withPassword("test");
        INSTANCE.start();
    }

    private SharedPostgresContainer() {}
}